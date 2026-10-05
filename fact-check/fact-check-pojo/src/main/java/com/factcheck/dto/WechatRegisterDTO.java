package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 微信小程序**注册新账号**入参
 *
 * <h3>与 {@link WechatBindDTO} 的区别</h3>
 * 小程序端登录时若该微信未绑定任何账号，此前只有"绑定既有 Web 账号"一条路：
 * <pre>
 * 未绑定 → 只能输入 Web 端已有的用户名/密码
 * </pre>
 * 没有 Web 账号的新用户就被挡住了（产品文档 S11 只描述了"绑定 Web 账号"，
 * 但实际使用中"新用户无法注册"是明显缺口）。
 *
 * <p>本 DTO 用于另一条路：<b>直接创建新账号，并把当前微信绑上去</b>，
 * 一步完成注册 + 绑定，用户不需要先跑去 Web 端注册。</p>
 *
 * <p>{@code code} 为可选：带上时会在同一事务里把该微信绑到新账号上，
 * 于是"注册"与"绑定"是一个原子操作 —— 不会出现"账号建好了但没绑上、
 * 用户拿着微信仍然登不进去"的中间态。</p>
 */
@Data
public class WechatRegisterDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 用户名（必填）。密码强度规则见 ValidationUtils.requireStrongPassword */
    private String username;
    /** 密码（必填，8~64 位且含字母与数字） */
    private String password;
    /** 昵称（可选，为空时取用户名） */
    private String nickname;
    /**
     * wx.login 返回的 code（可选）。
     * 带上时会把该微信绑到新账号；为空则只注册、不绑定。
     */
    private String code;
}
