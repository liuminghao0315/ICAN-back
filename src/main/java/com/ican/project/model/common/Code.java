package com.ican.project.model.common;

public class Code {
    //注册时——用户已存在
    public static final Integer USER_EXISTS = 1;
    //注册时——邮箱已存在
    public static final Integer EMAIL_EXISTS = 2;

    //获取验证码时——邮箱不为系统支持的邮箱
    public static final Integer EMAIL_NOT_SUPPORT = 3;

    //注册时——验证码不存在或失效
    public static final Integer VERIFY_CODE_NOT_EXISTS = 4;

    //重置密码时——用户不存在
    public static final Integer USER_NOT_EXISTS = 5;

    //重置密码时——新旧密码相同
    public static final Integer NEW_PWD_EQ_OLD = 6;

    //成功
    public static final Integer SUCCESS = 200;

    //参数错误
    public static final Integer PARAMETER_ERROR = 400;

    //认证失败（未登录）
    public static final Integer AUTH_FAILURE = 401;

    //鉴权失败（权限不够）
    public static final Integer ACCESS_FAILURE = 403;

    //请求路径不存在
    public static final Integer NOT_FOUND = 404;

    //请求方法不支持
    public static final Integer METHOD_NOT_ALLOWED = 405;

    //数据库错误
    public static final Integer DATABASE_ERROR = 500;

    //系统内部错误
    public static final Integer INTERNAL_ERROR = 500;
}
