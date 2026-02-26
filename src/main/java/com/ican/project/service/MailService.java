package com.ican.project.service;

import com.ican.project.model.common.Result;
import org.springframework.web.bind.annotation.RequestParam;

public interface MailService {
    Result<?> sendMailToRegister(String mailTo);

    Result<?> sendMailToResetPwd(String mailTo);

    Result<?> sendMailToChangeEmail(String mailTo);

    Result<?> sendMailToChangePwd(String mailTo);
}
