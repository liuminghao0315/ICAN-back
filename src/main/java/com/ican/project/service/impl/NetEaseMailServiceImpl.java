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

@Service("netease")
public class NetEaseMailServiceImpl implements MailService {
    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    private final int toRegister = 1;

    private final int toResetPwd = 2;

    @Override
    public Result<?> sendMailToRegister(String mailTo) {
        return sendNetEaseMail(mailTo,toRegister);
    }

    @Override
    public Result<?> sendMailToResetPwd(String mailTo) {
        return sendNetEaseMail(mailTo,toResetPwd);
    }

    private Result<?> sendNetEaseMail(String mailTo, int type) {
        if (mailTo != null && (mailTo.endsWith("@163.com") || mailTo.endsWith("@126.com"))) {
            //是163或126邮箱
            String verificationCode = CodeUtil.generateCode();
            mailUtil.sendNetEaseMail(mailTo,"您的注册验证码", "您的验证码是：" + verificationCode + "，5分钟内有效。");
            if(type==toRegister){
                redisTemplate.opsForValue().set("verifyCodeToRegister:" + verificationCode, mailTo, 5, TimeUnit.MINUTES);
            }else if(type==toResetPwd){
                redisTemplate.opsForValue().set("verifyCodeToResetPwd:" + verificationCode, mailTo, 5, TimeUnit.MINUTES);
            }
            return Result.success("验证码发送成功");
        }
        return Result.fail(Code.EMAIL_NOT_SUPPORT,"不是系统支持的邮箱");
    }
}

