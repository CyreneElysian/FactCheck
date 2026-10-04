package com.factcheck.controller.user;

import com.factcheck.constant.JwtClaimsConstant;
import com.factcheck.constant.UserConstant;
import com.factcheck.dto.PasswordEditDTO;
import com.factcheck.dto.UserLoginDTO;
import com.factcheck.dto.UserProfileDTO;
import com.factcheck.dto.UserRegisterDTO;
import com.factcheck.dto.WechatBindDTO;
import com.factcheck.dto.WechatLoginDTO;
import com.factcheck.entity.User;
import com.factcheck.properties.JwtProperties;
import com.factcheck.result.Result;
import com.factcheck.service.UserService;
import com.factcheck.util.JwtUtil;
import com.factcheck.vo.UserLoginVO;
import com.factcheck.vo.UserProfileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户账号控制器（用户端）
 */
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result register(@RequestBody UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：{}", userRegisterDTO.getUsername());
        userService.register(userRegisterDTO);
        return Result.success();
    }

    /**
     * 用户/管理员登录
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
        log.info("用户登录：{}", userLoginDTO.getUsername());
        User user = userService.login(userLoginDTO);
        return Result.success(buildLoginVO(user));
    }

    /**
     * 微信小程序登录
     */
    @PostMapping("/wechat/login")
    public Result<UserLoginVO> wechatLogin(@RequestBody WechatLoginDTO wechatLoginDTO) {
        log.info("微信小程序登录：code={}", wechatLoginDTO.getCode());
        User user = userService.wechatLogin(wechatLoginDTO);
        return Result.success(buildLoginVO(user));
    }

    /**
     * 微信小程序绑定 Web 账号
     */
    @PostMapping("/wechat/bind")
    public Result<UserLoginVO> wechatBind(@RequestBody WechatBindDTO wechatBindDTO) {
        log.info("微信绑定Web账号：username={}", wechatBindDTO.getUsername());
        User user = userService.bindWechat(wechatBindDTO);
        return Result.success(buildLoginVO(user));
    }

    /**
     * 查询当前用户信息
     */
    @GetMapping("/profile")
    public Result<UserProfileVO> profile() {
        log.info("查询当前用户信息");
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
     * 修改用户资料
     */
    @PutMapping("/profile")
    public Result updateProfile(@RequestBody UserProfileDTO userProfileDTO) {
        log.info("修改用户资料：{}", userProfileDTO);
        userService.updateProfile(userProfileDTO);
        return Result.success();
    }

    /**
     * 修改密码
     */
    @PutMapping("/password")
    public Result updatePassword(@RequestBody PasswordEditDTO passwordEditDTO) {
        log.info("修改密码");
        userService.updatePassword(passwordEditDTO);
        return Result.success();
    }

    /**
     * 注销当前账号
     */
    @PostMapping("/cancel")
    public Result cancel() {
        log.info("用户注销账号");
        userService.cancelAccount();
        return Result.success();
    }

    /**
     * 根据用户信息生成 JWT 并组装登录返回视图
     */
    private UserLoginVO buildLoginVO(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        claims.put(JwtClaimsConstant.ROLE, user.getRole());
        String token;
        if(user.getRole()== UserConstant.ADMIN){
            token = JwtUtil.createJWT(jwtProperties.getAdminSecretKey(), jwtProperties.getAdminTtl(), claims);
        }else{
            token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);
        }
        return UserLoginVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .token(token)
                .build();
    }
}
