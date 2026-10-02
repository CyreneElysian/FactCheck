package com.factcheck.constant;

/**
 * 提示信息常量，提示文案统一管理
 */
public class MessageConstant {
    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_LOCKED = "账号已禁用或已注销";
    public static final String ACCOUNT_EXISTS = "用户名已存在";
    public static final String ALREADY_EXISTS = "已存在";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String USER_NOT_LOGIN = "用户未登录";
    public static final String ADMIN_NOT_LOGIN = "管理员未登录";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String UPLOAD_FILE_EMPTY = "上传文件不能为空";
    public static final String UPLOAD_TYPE_NOT_SUPPORTED = "仅支持 jpg/jpeg/png/webp 格式的图片";
    public static final String UPLOAD_SIZE_EXCEEDED = "图片大小不能超过 5MB";
    public static final String UPLOAD_CONTENT_INVALID = "文件内容不是有效的图片（仅支持真实的 jpg/png/webp）";
    public static final String OCR_FAILED = "图片识别失败";
    public static final String OCR_NOT_CONFIGURED = "图片识别服务未配置，暂不支持图片检测";
    public static final String CONTENT_ILLEGAL = "内容涉嫌违规，请修改后重试";
    public static final String RECORD_NOT_FOUND = "检测记录不存在";
    public static final String APPEAL_NOT_FOUND = "申诉记录不存在";
    public static final String RATE_LIMIT = "操作过于频繁，请稍后再试";
    public static final String WECHAT_LOGIN_FAILED = "微信登录失败";
    public static final String WECHAT_NOT_BOUND = "微信未绑定账号，请先绑定";
    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String DATA_INTEGRITY_VIOLATION = "数据完整性校验失败，请检查提交内容";
}
