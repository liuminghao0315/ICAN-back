package com.ican.project.model.common;

/**
 * 错误码常量类
 * 使用 int 类型，因为这些都是纯粹用于标记的数字，不需要装箱拆箱
 */
public class Code {
    private Code() {
        // 防止实例化
    }

    //注册时——用户已存在
    public static final int USER_EXISTS = 1;
    //注册时——邮箱已存在
    public static final int EMAIL_EXISTS = 2;

    //获取验证码时——邮箱不为系统支持的邮箱
    public static final int EMAIL_NOT_SUPPORT = 3;

    //注册时——验证码不存在或失效
    public static final int VERIFY_CODE_NOT_EXISTS = 4;

    //重置密码时——用户不存在
    public static final int USER_NOT_EXISTS = 5;

    //重置密码时——新旧密码相同
    public static final int NEW_PWD_EQ_OLD = 6;

    //成功
    public static final int SUCCESS = 200;

    //参数错误
    public static final int PARAMETER_ERROR = 400;

    //认证失败（未登录）
    public static final int AUTH_FAILURE = 401;

    //AccessToken过期，需要刷新（前端收到此code需要用refreshToken刷新）
    public static final int TOKEN_EXPIRED = 4011;

    //RefreshToken过期，需要重新登录
    public static final int REFRESH_TOKEN_EXPIRED = 4012;

    //鉴权失败（权限不够）
    public static final int ACCESS_FAILURE = 403;

    //请求路径不存在
    public static final int NOT_FOUND = 404;

    //请求方法不支持
    public static final int METHOD_NOT_ALLOWED = 405;

    //数据库错误
    public static final int DATABASE_ERROR = 500;

    //系统内部错误
    public static final int INTERNAL_ERROR = 500;
}
