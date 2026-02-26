package com.ican.project.service.impl;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
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

@Service(Constants.Email.NETEASE_SERVICE_BEAN)
public class NetEaseMailServiceImpl implements MailService {
    private static final Logger logger = LoggerFactory.getLogger(NetEaseMailServiceImpl.class);

    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result<?> sendMailToRegister(String mailTo) {
        logger.info("发送网易注册验证邮件: mailTo={}", mailTo);
        return sendNetEaseMail(mailTo, Constants.MailType.REGISTER);
    }

    @Override
    public Result<?> sendMailToResetPwd(String mailTo) {
        logger.info("发送网易重置密码验证邮件: mailTo={}", mailTo);
        return sendNetEaseMail(mailTo, Constants.MailType.RESET_PASSWORD);
    }

    @Override
    public Result<?> sendMailToChangeEmail(String mailTo) {
        logger.info("发送网易变更邮箱验证邮件: mailTo={}", mailTo);
        return sendNetEaseMail(mailTo, Constants.MailType.CHANGE_EMAIL);
    }

    @Override
    public Result<?> sendMailToChangePwd(String mailTo) {
        logger.info("发送网易修改密码验证邮件: mailTo={}", mailTo);
        return sendNetEaseMail(mailTo, Constants.MailType.CHANGE_PWD);
    }

    private Result<?> sendNetEaseMail(String mailTo, int type) {
        if (mailTo == null || mailTo.trim().isEmpty()) {
            logger.warn("邮箱地址为空");
            return Result.fail(Code.PARAMETER_ERROR, "邮箱地址不能为空");
        }

        if (!mailTo.endsWith(Constants.Email.NETEASE_163_SUFFIX) && !mailTo.endsWith(Constants.Email.NETEASE_126_SUFFIX)) {
            logger.warn("不是网易邮箱: mailTo={}", mailTo);
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

            String subject = type == Constants.MailType.REGISTER
                    ? Constants.MailContent.REGISTER_SUBJECT
                    : type == Constants.MailType.CHANGE_EMAIL
                        ? Constants.MailContent.CHANGE_EMAIL_SUBJECT
                        : type == Constants.MailType.CHANGE_PWD
                            ? Constants.MailContent.CHANGE_PWD_SUBJECT
                            : Constants.MailContent.RESET_PASSWORD_SUBJECT;
            String content = Constants.MailContent.generateContent(verificationCode, Constants.VerifyCode.EXPIRE_MINUTES);

            try {
                mailUtil.sendNetEaseMail(mailTo, subject, content);
                logger.debug("网易邮件发送成功: mailTo={}", mailTo);
            } catch (Exception e) {
                logger.error("网易邮件发送失败: mailTo={}", mailTo, e);
                return Result.fail(Code.INTERNAL_ERROR, "邮件发送失败，请稍后重试");
            }

            String redisKey = type == Constants.MailType.REGISTER
                    ? Constants.RedisKey.VERIFY_CODE_REGISTER_PREFIX + verificationCode
                    : type == Constants.MailType.CHANGE_EMAIL
                        ? Constants.RedisKey.VERIFY_CODE_CHANGE_EMAIL_PREFIX + verificationCode
                        : type == Constants.MailType.CHANGE_PWD
                            ? Constants.RedisKey.VERIFY_CODE_CHANGE_PWD_PREFIX + verificationCode
                            : Constants.RedisKey.VERIFY_CODE_RESET_PWD_PREFIX + verificationCode;
            try {
                redisTemplate.opsForValue().set(redisKey, mailTo, Constants.VerifyCode.EXPIRE_MINUTES, TimeUnit.MINUTES);
                logger.debug("验证码已存储到Redis: redisKey={}, mailTo={}", redisKey, mailTo);
            } catch (Exception e) {
                logger.error("验证码存储到Redis失败: redisKey={}", redisKey, e);
                return Result.fail(Code.INTERNAL_ERROR, "验证码存储失败，请稍后重试");
            }

            logger.info("网易验证码发送成功: mailTo={}, type={}", mailTo, type == Constants.MailType.REGISTER ? "注册" : "重置密码");
            return Result.success("验证码发送成功");
        } catch (Exception e) {
            logger.error("发送网易邮件异常: mailTo={}, type={}", mailTo, type, e);
            throw e; // 让全局异常处理器处理
        }
    }
}

