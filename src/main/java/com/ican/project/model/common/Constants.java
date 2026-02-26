package com.ican.project.model.common;

/**
 * 系统常量类
 * 统一管理硬编码的字符串常量
 */
public class Constants {
    private Constants() {
        // 防止实例化
    }

    /**
     * Redis Key 前缀
     */
    public static class RedisKey {
        private RedisKey() {}

        /** 用户ID Redis Key 前缀 */
        public static final String USER_ID_PREFIX = "userId:";

        /** 注册验证码 Redis Key 前缀 */
        public static final String VERIFY_CODE_REGISTER_PREFIX = "verifyCodeToRegister:";

        /** 重置密码验证码 Redis Key 前缀 */
        public static final String VERIFY_CODE_RESET_PWD_PREFIX = "verifyCodeToResetPwd:";

        /** 变更邮箱验证码 Redis Key 前缀（value 存新邮箱） */
        public static final String VERIFY_CODE_CHANGE_EMAIL_PREFIX = "verifyCodeToChangeEmail:";

        /** 修改密码验证码 Redis Key 前缀（value 存 userId） */
        public static final String VERIFY_CODE_CHANGE_PWD_PREFIX = "verifyCodeToChangePwd:";
    }

    /**
     * HTTP 请求相关常量
     */
    public static class Http {
        private Http() {}

        /** Authorization Header 前缀 */
        public static final String BEARER_PREFIX = "Bearer ";

        /** Authorization Header 名称 */
        public static final String AUTHORIZATION_HEADER = "Authorization";

        /** Swagger Security Scheme 名称 */
        public static final String SWAGGER_SECURITY_SCHEME = "Bearer Authentication";
    }

    /**
     * 邮箱相关常量
     */
    public static class Email {
        private Email() {}

        /** QQ邮箱后缀 */
        public static final String QQ_SUFFIX = "@qq.com";

        /** 163邮箱后缀 */
        public static final String NETEASE_163_SUFFIX = "@163.com";

        /** 126邮箱后缀 */
        public static final String NETEASE_126_SUFFIX = "@126.com";

        /** QQ邮箱服务Bean名称 */
        public static final String QQ_SERVICE_BEAN = "qq";

        /** 网易邮箱服务Bean名称 */
        public static final String NETEASE_SERVICE_BEAN = "netease";
    }

    /**
     * 验证码相关常量
     */
    public static class VerifyCode {
        private VerifyCode() {}

        /** 验证码长度 */
        public static final int LENGTH = 6;

        /** 验证码有效时长（分钟） */
        public static final int EXPIRE_MINUTES = 5;
    }

    /**
     * 邮件类型常量
     */
    public static class MailType {
        private MailType() {}

        /** 注册邮件类型 */
        public static final int REGISTER = 1;

        /** 重置密码邮件类型 */
        public static final int RESET_PASSWORD = 2;

        /** 变更邮箱邮件类型 */
        public static final int CHANGE_EMAIL = 3;

        /** 修改密码邮件类型（登录态，发到当前邮箱） */
        public static final int CHANGE_PWD = 4;
    }

    /**
     * 角色相关常量
     */
    public static class Role {
        private Role() {}

        /** 默认用户角色ID */
        public static final String DEFAULT_USER_ROLE_ID = "102";
    }

    /**
     * 邮件内容常量
     */
    public static class MailContent {
        private MailContent() {}

        /** 注册验证码邮件主题 */
        public static final String REGISTER_SUBJECT = "您的注册验证码";

        /** 重置密码验证码邮件主题 */
        public static final String RESET_PASSWORD_SUBJECT = "您的重置密码验证码";

        /** 变更邮箱验证码邮件主题 */
        public static final String CHANGE_EMAIL_SUBJECT = "变更绑定邮箱验证码";

        /** 修改密码验证码邮件主题 */
        public static final String CHANGE_PWD_SUBJECT = "修改密码验证码";

        /** 验证码邮件内容模板 */
        private static final String CONTENT_TEMPLATE = "您的验证码是：%s，%d分钟内有效。";

        /**
         * 生成验证码邮件内容
         * @param code 验证码
         * @param expireMinutes 过期时间（分钟）
         * @return 邮件内容
         */
        public static String generateContent(String code, int expireMinutes) {
            return String.format(CONTENT_TEMPLATE, code, expireMinutes);
        }
    }
}

