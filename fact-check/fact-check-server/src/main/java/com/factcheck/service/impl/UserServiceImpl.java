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
import com.factcheck.dto.WechatRegisterDTO;
import com.factcheck.entity.OperationLog;
import com.factcheck.entity.User;
import com.factcheck.exception.AccountExistsException;
import com.factcheck.exception.AccountLockedException;
import com.factcheck.exception.AccountNotFoundException;
import com.factcheck.exception.LoginFailedException;
import com.factcheck.exception.PasswordErrorException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import com.factcheck.service.HotRankService;
import com.factcheck.service.RateLimitService;
import com.factcheck.service.UserService;
import com.factcheck.util.ClientIpUtils;
import com.factcheck.util.LogMaskUtil;
import com.factcheck.util.ValidationUtils;
import com.factcheck.util.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private HotRankService hotRankService;

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
        // 密码强度校验：此前只有"非空 + 长度 ≤64"，123456 / password 都能直接设置
        String password = ValidationUtils.requireStrongPassword(userRegisterDTO.getPassword(), username);
        String nickname = ValidationUtils.optionalText(userRegisterDTO.getNickname(), "昵称", 50);

        User user = createUser(username, password, nickname);
        log.info("用户注册成功：userId={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    /**
     * 创建用户（不含限流与注册日志）——供 {@link #register} 与 {@link #registerByWechat} 复用
     *
     * <p>抽出来是为了让"小程序注册"与"Web 注册"走**同一套**用户名唯一性与密码强度校验，
     * 避免两条注册路径的规则漂移（将来改密码策略只需改一处）。</p>
     *
     * <p>入参假定已完成 {@code requireText}/{@code requireStrongPassword} 校验。</p>
     */
    private User createUser(String username, String encodedPasswordInput, String nickname) {
        User exist = userMapper.getByUsername(username);
        if (exist != null) {
            throw new AccountExistsException(MessageConstant.ACCOUNT_EXISTS);
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(encodedPasswordInput))
                .nickname(nickname != null ? nickname : username)
                .role(0)
                .status(StatusConstant.ENABLE)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        return user;
    }

    /**
     * 微信小程序注册新账号（可选同时绑定当前微信）
     *
     * <h3>为什么需要它</h3>
     * 小程序端此前在"该微信未绑定任何账号"时**只给一条路**：输入 Web 端已有的
     * 用户名/密码去绑定。没有 Web 账号的新用户直接被挡住。
     * 现在小程序端会给出两个选择：<b>创建新账号</b> 或 <b>绑定已有账号</b>。
     *
     * <h3>为什么注册与绑定要放同一个事务</h3>
     * 若分两步（先注册、再绑定），绑定失败时用户会拿到一个"存在但没绑微信"的账号 ——
     * 他不知道自己已经注册成功，再试一次又会撞"用户名已存在"，
     * 且用微信登录仍然进不去。放在一个事务里就不会留下这种中间态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public User registerByWechat(WechatRegisterDTO wechatRegisterDTO) {
        log.info("微信小程序注册：username={}",
                wechatRegisterDTO == null ? null : wechatRegisterDTO.getUsername());

        // 与 Web 注册共用同一套防刷限流（同样按 IP）：
        // 否则小程序端会变成绕过注册限流的旁路
        rateLimitService.checkRegister(ClientIpUtils.currentIp());

        String username = ValidationUtils.requireText(wechatRegisterDTO.getUsername(), "用户名", 50);
        String password = ValidationUtils.requireStrongPassword(wechatRegisterDTO.getPassword(), username);
        String nickname = ValidationUtils.optionalText(wechatRegisterDTO.getNickname(), "昵称", 50);

        String openid = null;
        String unionid = null;
        if (StringUtils.hasLength(wechatRegisterDTO.getCode())) {
            JSONObject session = callWechatCode2Session(wechatRegisterDTO.getCode());
            openid = session.getString("openid");
            unionid = session.getString("unionid");
            if (!StringUtils.hasLength(openid)) {
                throw new LoginFailedException(MessageConstant.WECHAT_LOGIN_FAILED);
            }
            // 该微信已绑过别的账号：不能再拿它注册新号，否则等于一个人多开
            if (userMapper.getByOpenid(openid) != null) {
                throw new AccountExistsException("该微信已绑定其他账号");
            }
        }

        User user = createUser(username, password, nickname);

        if (openid != null) {
            userMapper.update(User.builder()
                    .id(user.getId())
                    .wechatOpenid(openid)
                    .wechatUnionid(unionid)
                    .updateTime(LocalDateTime.now())
                    .build());
            log.info("微信已绑定到新账号：userId={}, username={}", user.getId(), username);
        }

        // 重新查一次，保证返回的 User 带上刚写入的 openid（前端要据此判断是否已绑定）
        return userMapper.getById(user.getId());
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
        // 日志脱敏：wx.login 的 code 可被用来换取 openid/session_key，只记打码值
        log.info("微信小程序登录：code={}", LogMaskUtil.mask(wechatLoginDTO.getCode()));
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
        // 按 IP 限流（P2-16 同类缺口补齐）：本接口在鉴权白名单里，且每次调用都会请求微信
        // code2session，不限流等于给外部一个"刷微信接口"的跳板。放在最前面，
        // 与注册一致——**错误请求同样消耗配额**，否则枚举尝试不受约束。
        rateLimitService.checkWechatBind(ClientIpUtils.currentIp());
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
        // 密码强度校验（**只作用于设置新密码的路径，不拦登录**：
        // 历史弱口令账号仍可登录，只在改密时被要求变强，避免"加固把用户锁在门外"）
        ValidationUtils.requireStrongPassword(passwordEditDTO.getNewPassword(), user.getUsername());
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
        // 注销后把该用户的记录从公开热度榜摘下来（与管理员注销路径一致）：
        // 热度榜是公开 Top10，产品文档 §4.5 要求注销用户的记录保留但脱敏展示。
        try {
            List<Long> recordIds = factCheckRecordMapper.listIdsByUser(userId);
            if (recordIds != null && !recordIds.isEmpty()) {
                long removed = hotRankService.remove(recordIds.toArray(new Long[0]));
                log.info("用户自助注销：已从热度榜摘除其记录 {} 条（实际命中 {} 条）", recordIds.size(), removed);
            }
        } catch (Exception e) {
            // 摘榜失败不影响注销本身（热度是辅助功能）
            log.warn("用户自助注销后摘除热度榜失败：userId={}, error={}", userId, e.getMessage());
        }
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
     *
     * <p>可见性为 {@code protected} 而非 {@code private}：这是**刻意的测试接缝**。
     * 它内部会发起真实的 HTTPS 请求到微信，而本项目的单测一律离线
     * （见各测试类的注释与 {@code _NetworkGuard}）。原先它是 private，
     * 导致"微信 openid 相关分支"（例如"该微信已绑定其他账号"）无法在单测覆盖。
     * 子类覆写本方法即可注入预置 session，无需引入 mockito-inline 之类的额外依赖。</p>
     */
    protected JSONObject callWechatCode2Session(String code) {
        Map<String, String> params = new HashMap<>();
        params.put("appid", weChatProperties.getAppid());
        params.put("secret", weChatProperties.getSecret());
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");
        String response = HttpClientUtil.doGet(WECHAT_CODE2SESSION_URL, params);
        // 日志脱敏：code2session 响应含 openid / session_key / unionid，
        // session_key 尤其敏感（可用于解密用户数据）。保留首尾各几个字符，
        // 足以比对"两次调用是不是同一个用户"，但不把凭据整段留档。
        log.info("微信 code2session 响应：{}",
                LogMaskUtil.maskSensitiveJsonFields(response, "openid", "session_key", "unionid"));
        JSONObject json = JSONObject.parseObject(response);
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            throw new LoginFailedException(MessageConstant.WECHAT_LOGIN_FAILED);
        }
        return json;
    }
}
