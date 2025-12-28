package com.ican.project.service.impl;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.service.MailService;
import com.ican.project.utils.CodeUtil;
import com.ican.project.utils.MailUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service("qq")
public class QQMailServiceImpl implements MailService {
    private static final Logger logger = LoggerFactory.getLogger(QQMailServiceImpl.class);

    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final int toRegister = 1;

    private final int toResetPwd = 2;

    @Override
    public Result<?> sendMailToRegister(String mailTo) {
        logger.info("发送QQ注册验证邮件: mailTo={}", mailTo);
        return sendQQMail(mailTo, toRegister);
    }

    @Override
    public Result<?> sendMailToResetPwd(String mailTo) {
        logger.info("发送QQ重置密码验证邮件: mailTo={}", mailTo);
        return sendQQMail(mailTo, toResetPwd);
    }

    private Result<?> sendQQMail(String mailTo, int type) {
        if (mailTo == null || mailTo.trim().isEmpty()) {
            logger.warn("邮箱地址为空");
            return Result.fail(Code.PARAMETER_ERROR, "邮箱地址不能为空");
        }

        if (!mailTo.endsWith("@qq.com")) {
            logger.warn("不是QQ邮箱: mailTo={}", mailTo);
            return Result.fail(Code.EMAIL_NOT_SUPPORT, "不是系统支持的邮箱");
        }

        if (mailUtil == null) {
            logger.error("邮件工具类未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "邮件服务未初始化");
        }

        if (redisTemplate == null) {
            logger.error("Redis模板未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "缓存服务未初始化");
        }

        try {
            String verificationCode = CodeUtil.generateCode();
            if (verificationCode == null || verificationCode.trim().isEmpty()) {
                logger.error("验证码生成失败");
                return Result.fail(Code.INTERNAL_ERROR, "验证码生成失败");
            }

            String subject = type == toRegister ? "您的注册验证码" : "您的重置密码验证码";
            String content = "您的验证码是：" + verificationCode + "，5分钟内有效。";

            try {
                mailUtil.sendQQMail(mailTo, subject, content);
                logger.debug("QQ邮件发送成功: mailTo={}", mailTo);
            } catch (Exception e) {
                logger.error("QQ邮件发送失败: mailTo={}", mailTo, e);
                return Result.fail(Code.INTERNAL_ERROR, "邮件发送失败，请稍后重试");
            }

            String redisKey = type == toRegister ? "verifyCodeToRegister:" + verificationCode : "verifyCodeToResetPwd:" + verificationCode;
            try {
                redisTemplate.opsForValue().set(redisKey, mailTo, 5, TimeUnit.MINUTES);
                logger.debug("验证码已存储到Redis: redisKey={}, mailTo={}", redisKey, mailTo);
            } catch (Exception e) {
                logger.error("验证码存储到Redis失败: redisKey={}", redisKey, e);
                return Result.fail(Code.INTERNAL_ERROR, "验证码存储失败，请稍后重试");
            }

            logger.info("QQ验证码发送成功: mailTo={}, type={}", mailTo, type == toRegister ? "注册" : "重置密码");
            return Result.success("验证码发送成功");
        } catch (Exception e) {
            logger.error("发送QQ邮件异常: mailTo={}, type={}", mailTo, type, e);
            throw e; // 让全局异常处理器处理
        }
    }
}
