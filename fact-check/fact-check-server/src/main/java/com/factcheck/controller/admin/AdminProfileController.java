package com.factcheck.controller.admin;

import com.factcheck.dto.PasswordEditDTO;
import com.factcheck.dto.UserProfileDTO;
import com.factcheck.entity.User;
import com.factcheck.result.Result;
import com.factcheck.service.UserService;
import com.factcheck.vo.UserProfileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员个人中心控制器
 */
@RestController
@RequestMapping("/admin/profile")
@Slf4j
public class AdminProfileController {

    @Autowired
    private UserService userService;

    /**
     * 查询当前管理员信息
     */
    @GetMapping
    public Result<UserProfileVO> profile() {
        log.info("管理员查询个人信息");
        User user = userService.getProfile();
        return Result.success(UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .status(user.getStatus())
                .build());
    }

    /**
     * 修改管理员资料
     */
    @PutMapping
    public Result updateProfile(@RequestBody UserProfileDTO userProfileDTO) {
        log.info("管理员修改个人资料：{}", userProfileDTO);
        userService.updateProfile(userProfileDTO);
        return Result.success();
    }

    /**
     * 修改管理员密码
     */
    @PutMapping("/password")
    public Result updatePassword(@RequestBody PasswordEditDTO passwordEditDTO) {
        log.info("管理员修改密码");
        userService.updatePassword(passwordEditDTO);
        return Result.success();
    }
}
