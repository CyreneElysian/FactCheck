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
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AdminAccountService;
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
        if (!StringUtils.hasLength(adminAccountCreateDTO.getPassword())) {
            throw new BaseException("请填写密码");
        }

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
        if (status == null || (status != 0 && status != 1)) {
            throw new BaseException("用户状态只能为：1正常、0禁用");
        }
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        if (user.getRole() != null && user.getRole() == 1) {
            throw new BaseException("不能禁用管理员账号");
        }
        userMapper.update(User.builder()
                .id(userId)
                .status(status)
                .updateTime(LocalDateTime.now())
                .build());

        saveLog(adminId, status == 1 ? "启用用户" : "禁用用户", "userId=" + userId);
        log.info("用户状态修改成功：userId={}, status={}", userId, status);
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
