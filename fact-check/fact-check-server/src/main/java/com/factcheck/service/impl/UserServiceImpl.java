package com.factcheck.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.StatusConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.PasswordEditDTO;
import com.factcheck.dto.UserLoginDTO;
import com.factcheck.dto.UserProfileDTO;
import com.factcheck.dto.UserRegisterDTO;
import com.factcheck.dto.WechatBindDTO;
import com.factcheck.dto.WechatLoginDTO;
import com.factcheck.entity.OperationLog;
import com.factcheck.entity.User;
import com.factcheck.exception.AccountExistsException;
import com.factcheck.exception.AccountLockedException;
import com.factcheck.exception.AccountNotFoundException;
import com.factcheck.exception.LoginFailedException;
import com.factcheck.exception.PasswordErrorException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import com.factcheck.service.RateLimitService;
import com.factcheck.service.UserService;
import com.factcheck.util.ClientIpUtils;
import com.factcheck.util.ValidationUtils;
import com.factcheck.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务实现
 */
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private static final String WECHAT_CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private RateLimitService rateLimitService;
    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 用户注册
     */
    @Override
    public User register(UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：username={}", userRegisterDTO == null ? null : userRegisterDTO.getUsername());

        // 防刷限流（S9 / P2-16）：按客户端 IP 限制注册频次，防止批量注册小号
        rateLimitService.checkRegister(ClientIpUtils.currentIp());

        // 参数校验（P2-17）：原先**完全没有校验**——密码为 null 会直接在
        // passwordEncoder.encode(null) 抛 NPE（前端只能看到"未知错误"），
        // 超长用户名/昵称则要等到写库时才报数据完整性错误。
        String username = ValidationUtils.requireText(userRegisterDTO.getUsername(), "用户名", 50);
        String password = ValidationUtils.requireText(userRegisterDTO.getPassword(), "密码", 64);
        String nickname = ValidationUtils.optionalText(userRegisterDTO.getNickname(), "昵称", 50);

        User exist = userMapper.getByUsername(username);
        if (exist != null) {
            throw new AccountExistsException(MessageConstant.ACCOUNT_EXISTS);
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .nickname(nickname != null ? nickname : username)
                .role(0)
                .status(StatusConstant.ENABLE)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        log.info("用户注册成功：userId={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    /**
     * 用户/管理员登录
     */
    @Override
    public User login(UserLoginDTO userLoginDTO) {
        log.info("用户登录：username={}", userLoginDTO == null ? null : userLoginDTO.getUsername());

        // 防爆破限流（S9 / P2-16）：按用户名 + 按 IP 双维度，计数在所有尝试之前，
        // 因此"错误密码尝试"同样消耗配额。放在参数校验之前是刻意的：
        // 即使请求体不合法也属于一次尝试，不该绕过计数。
        // 只限流、不锁定账号——硬锁会被用来把真实用户锁在门外（见 RateLimitService 注释）。
        rateLimitService.checkLogin(
                userLoginDTO == null ? null : userLoginDTO.getUsername(),
                ClientIpUtils.currentIp());

        // 参数校验（P2-17）：缺参时给明确提示，而不是把 null 带进 mapper 再变成"未知错误"
        String username = ValidationUtils.requireText(userLoginDTO.getUsername(), "用户名", 50);
        String password = ValidationUtils.requireText(userLoginDTO.getPassword(), "密码", 64);

        User user = userMapper.getByUsername(username);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        if (!StatusConstant.ENABLE.equals(user.getStatus())) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }
        boolean isAdmin = user.getRole() != null && user.getRole() == 1;
        if (!passwordEncoder.matches(password, user.getPassword())) {
            if (isAdmin) {
                // 只对**真实存在的管理员账号**记录失败日志：否则任意用户名都能往 t_operation_log 写一行，
                // 等于给攻击者一个放大写入的入口（登录本身另有频次限流兜底）
                saveAdminLoginLog(user.getId(), "管理员登录失败（密码错误）");
            }
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        User update = User.builder()
                .id(user.getId())
                .lastLoginTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.update(update);
        user.setLastLoginTime(update.getLastLoginTime());
        if (isAdmin) {
            // S3：管理员登录必须留痕（成功与失败都记，便于事后核查异常登录）
            saveAdminLoginLog(user.getId(), "管理员登录");
        }
        return user;
    }

    /**
     * 写管理员登录操作日志（P5-56）
     *
     * <p>审计写入失败不应影响登录本身，因此单独 try/catch 并记 ERROR。</p>
     */
    private void saveAdminLoginLog(Long adminId, String operation) {
        try {
            operationLogMapper.insert(OperationLog.builder()
                    .adminId(adminId)
                    .operation(operation)
                    .method("UserServiceImpl.login")
                    .params("adminId=" + adminId)
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("管理员登录日志写入失败：adminId={}, error={}", adminId, e.getMessage(), e);
        }
    }

    /**
     * 微信小程序登录
     */
    @Override
    public User wechatLogin(WechatLoginDTO wechatLoginDTO) {
        log.info("微信小程序登录：code={}", wechatLoginDTO.getCode());
        String openid = getOpenidByCode(wechatLoginDTO.getCode());
        User user = userMapper.getByOpenid(openid);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.WECHAT_NOT_BOUND);
        }
        if (!StatusConstant.ENABLE.equals(user.getStatus())) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }
        return user;
    }

    /**
     * 微信小程序绑定 Web 账号
     */
    @Override
    public User bindWechat(WechatBindDTO wechatBindDTO) {
        log.info("微信绑定Web账号：username={}", wechatBindDTO.getUsername());
        JSONObject session = callWechatCode2Session(wechatBindDTO.getCode());
        String openid = session.getString("openid");
        if (!StringUtils.hasLength(openid)) {
            throw new LoginFailedException(MessageConstant.WECHAT_LOGIN_FAILED);
        }
        User boundUser = userMapper.getByOpenid(openid);
        if (boundUser != null) {
            throw new AccountExistsException("该微信已绑定其他账号");
        }

        User user = userMapper.getByUsername(wechatBindDTO.getUsername());
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        if (!StatusConstant.ENABLE.equals(user.getStatus())) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(wechatBindDTO.getPassword(), user.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        User update = User.builder()
                .id(user.getId())
                .wechatOpenid(openid)
                .wechatUnionid(session.getString("unionid"))
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.update(update);
        return userMapper.getById(user.getId());
    }

    /**
     * 查询当前用户信息
     */
    @Override
    public User getProfile() {
        Long userId = BaseContext.getCurrentId();
        log.info("查询当前用户信息：userId={}", userId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        return user;
    }

    /**
     * 修改用户资料
     */
    @Override
    public void updateProfile(UserProfileDTO userProfileDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("修改用户资料：userId={}, nickname={}, avatar={}", userId, userProfileDTO.getNickname(), userProfileDTO.getAvatar());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        User update = User.builder()
                .id(userId)
                .nickname(userProfileDTO.getNickname())
                .avatar(userProfileDTO.getAvatar())
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.update(update);
    }

    /**
     * 修改密码
     */
    @Override
    public void updatePassword(PasswordEditDTO passwordEditDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("修改密码：userId={}", userId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        if (!passwordEncoder.matches(passwordEditDTO.getOldPassword(), user.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }
        User update = User.builder()
                .id(userId)
                .password(passwordEncoder.encode(passwordEditDTO.getNewPassword()))
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.update(update);
    }

    /**
     * 注销当前账号
     */
    @Override
    public void cancelAccount() {
        Long userId = BaseContext.getCurrentId();
        log.info("用户注销账号：userId={}", userId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        userMapper.cancel(userId);
    }

    /**
     * 调用微信 code2session 接口，用 code 换 openid
     */
    private String getOpenidByCode(String code) {
        JSONObject json = callWechatCode2Session(code);
        String openid = json.getString("openid");
        if (!StringUtils.hasLength(openid)) {
            throw new LoginFailedException(MessageConstant.WECHAT_LOGIN_FAILED);
        }
        return openid;
    }

    /**
     * 调用微信 code2session 接口，获取 unionid（可能为空）
     */
    private String getUnionidByCode(String code) {
        JSONObject json = callWechatCode2Session(code);
        return json.getString("unionid");
    }

    /**
     * 调用微信 code2session 接口
     */
    private JSONObject callWechatCode2Session(String code) {
        Map<String, String> params = new HashMap<>();
        params.put("appid", weChatProperties.getAppid());
        params.put("secret", weChatProperties.getSecret());
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");
        String response = HttpClientUtil.doGet(WECHAT_CODE2SESSION_URL, params);
        log.info("微信 code2session 响应：{}", response);
        JSONObject json = JSONObject.parseObject(response);
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            throw new LoginFailedException(MessageConstant.WECHAT_LOGIN_FAILED);
        }
        return json;
    }
}
