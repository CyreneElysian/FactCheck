package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.StatusConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminAccountCreateDTO;
import com.factcheck.entity.OperationLog;
import com.factcheck.entity.User;
import com.factcheck.exception.AccountExistsException;
import com.factcheck.exception.AccountNotFoundException;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AdminAccountService;
import com.factcheck.service.HotRankService;
import com.factcheck.util.ValidationUtils;
import com.factcheck.vo.AdminBriefVO;
import com.factcheck.vo.UserProfileVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理员账号管理服务实现
 */
@Service
@Slf4j
public class AdminAccountServiceImpl implements AdminAccountService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private HotRankService hotRankService;

    /**
     * 查询管理员简要列表（P3-35：诊断转派下拉框）
     *
     * <p>转派原先只能让管理员手填一个 ID——填错就是一个无效指派，
     * 这里提供真实可选的管理员列表。只返回 id/username/nickname。</p>
     */
    @Override
    public List<AdminBriefVO> listAdmins() {
        return userMapper.listAdmins().stream()
                .map(u -> AdminBriefVO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .nickname(u.getNickname())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 创建管理员账号
     */
    @Override
    public void create(AdminAccountCreateDTO adminAccountCreateDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员创建管理员账号：adminId={}, username={}", adminId, adminAccountCreateDTO.getUsername());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        if (!StringUtils.hasLength(adminAccountCreateDTO.getUsername())) {
            throw new BaseException("请填写用户名");
        }
        // 管理员账号权限更高，密码强度与普通用户同一套规则（见 ValidationUtils.requireStrongPassword）
        ValidationUtils.requireStrongPassword(adminAccountCreateDTO.getPassword(),
                adminAccountCreateDTO.getUsername());

        User exist = userMapper.getByUsername(adminAccountCreateDTO.getUsername());
        if (exist != null) {
            throw new AccountExistsException(MessageConstant.ACCOUNT_EXISTS);
        }

        User user = User.builder()
                .username(adminAccountCreateDTO.getUsername())
                .password(passwordEncoder.encode(adminAccountCreateDTO.getPassword()))
                .nickname(StringUtils.hasLength(adminAccountCreateDTO.getNickname())
                        ? adminAccountCreateDTO.getNickname() : adminAccountCreateDTO.getUsername())
                .role(1)
                .status(StatusConstant.ENABLE)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);

        saveLog(adminId, "创建管理员账号", "username=" + adminAccountCreateDTO.getUsername());
        log.info("管理员账号创建成功：newAdminId={}, username={}", user.getId(), user.getUsername());
    }

    /**
     * 分页查询用户列表
     */
    @Override
    public PageResult pageUsers(Integer page, Integer pageSize) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员分页查询用户列表：adminId={}, page={}, pageSize={}", adminId, page, pageSize);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        // 参数校验（P2-17）：page=0 会被 PageHelper 当成"不分页"直接返回全表，pageSize 过大同样会全表加载
        ValidationUtils.requirePaging(page, pageSize);
        int pageNum = page == null ? 1 : page;
        int size = pageSize == null ? 10 : pageSize;
        PageHelper.startPage(pageNum, size);
        List<User> list = userMapper.pageAll();
        Page<User> pageInfo = (Page<User>) list;
        List<UserProfileVO> records = list.stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }

    /**
     * 启用/禁用用户
     */
    @Override
    public void updateUserStatus(Long userId, Integer status) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员修改用户状态：adminId={}, userId={}, status={}", adminId, userId, status);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        // P5-55：支持 0 禁用 / 1 正常 / 2 注销（产品文档 §4.5 的用户状态含"已注销"）
        if (status == null || (status != 0 && status != 1 && status != 2)) {
            throw new BaseException("用户状态只能为：1正常、0禁用、2注销");
        }
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        if (user.getRole() != null && user.getRole() == 1) {
            throw new BaseException(status == 2 ? "不能注销管理员账号" : "不能禁用管理员账号");
        }
        userMapper.update(User.builder()
                .id(userId)
                .status(status)
                .updateTime(LocalDateTime.now())
                .build());

        // 注销（status=2）后把该用户的记录从公开热度榜摘下来（产品文档 §4.5：记录保留但脱敏展示，
        // 继续占据公开 Top10 与"脱敏"的意图相悖）。只查主键，避免把大字段拉进内存。
        if (status != null && status == 2) {
            detachUserRecordsFromHotRank(userId);
        }

        // P2-18 修正：原先 status==2 也被记成"禁用用户"，审计日志与操作不符
        String operation = status == 1 ? "启用用户" : (status == 2 ? "注销用户" : "禁用用户");
        saveLog(adminId, operation, "userId=" + userId);
        log.info("用户状态修改成功：userId={}, status={}", userId, status);
    }

    /**
     * 把某个用户的全部记录从热度榜移除
     *
     * <p>热度榜是**公开** Top10，用户注销后其记录不应继续出现在公开榜单里。
     * 本方法只负责摘榜，不动数据库（记录本身按产品要求保留）。</p>
     */
    private void detachUserRecordsFromHotRank(Long userId) {
        try {
            List<Long> recordIds = factCheckRecordMapper.listIdsByUser(userId);
            if (recordIds == null || recordIds.isEmpty()) {
                return;
            }
            long removed = hotRankService.remove(recordIds.toArray(new Long[0]));
            log.info("用户注销：已从热度榜摘除其记录 {} 条（实际命中 {} 条）", recordIds.size(), removed);
        } catch (Exception e) {
            // 摘榜失败不影响注销本身（热度是辅助功能）
            log.warn("用户注销后摘除热度榜失败：userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 用户实体转视图（不返回密码）
     */
    private UserProfileVO toVO(User user) {
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .status(user.getStatus())
                .build();
    }

    /**
     * 保存操作日志
     */
    private void saveLog(Long adminId, String operation, String params) {
        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation(operation)
                .method("AdminAccountServiceImpl")
                .params(params)
                .createTime(LocalDateTime.now())
                .build());
    }
}
