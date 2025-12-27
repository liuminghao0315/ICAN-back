package com.ican.project.service.impl;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.service.MailService;
import com.ican.project.utils.CodeUtil;
import com.ican.project.utils.MailUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service("qq")
public class QQMailServiceImpl implements MailService {
    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Override
    public Result<?> sendMailToRegister(String mailTo) {
        if (mailTo != null && mailTo.endsWith("@qq.com")) {
            //是qq邮箱
            String verificationCode = CodeUtil.generateCode();
            mailUtil.sendQQMail(mailTo,"您的注册验证码", "您的验证码是：" + verificationCode + "，5分钟内有效。");
            redisTemplate.opsForValue().set("verifyCode:" + verificationCode, mailTo, 5, TimeUnit.MINUTES);
            return Result.success(verificationCode);
        }
        return Result.fail(Code.EMAIL_NOT_SUPPORT,"不是系统支持的邮箱");
    }


}
