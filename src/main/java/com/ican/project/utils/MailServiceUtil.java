package com.ican.project.utils;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 邮件服务工具类
 * 提供统一的邮件服务获取逻辑
 */
public class MailServiceUtil {
    private static final Logger logger = LoggerFactory.getLogger(MailServiceUtil.class);

    private MailServiceUtil() {
        // 防止实例化
    }

    /**
     * 根据邮箱地址获取对应的邮件服务
     * @param mailServiceMap 邮件服务Map
     * @param email 邮箱地址
     * @return 邮件服务，如果未找到返回null
     */
    public static MailService getMailServiceByEmail(Map<String, MailService> mailServiceMap, String email) {
        if (mailServiceMap == null || mailServiceMap.isEmpty()) {
            logger.error("邮件服务Map未初始化");
            return null;
        }

        if (email == null || email.trim().isEmpty()) {
            logger.warn("邮箱地址为空");
            return null;
        }

        if (email.endsWith(Constants.Email.QQ_SUFFIX)) {
            MailService service = mailServiceMap.get(Constants.Email.QQ_SERVICE_BEAN);
            if (service == null) {
                logger.warn("QQ邮箱服务不存在");
            }
            return service;
        } else if (email.endsWith(Constants.Email.NETEASE_163_SUFFIX) || email.endsWith(Constants.Email.NETEASE_126_SUFFIX)) {
            MailService service = mailServiceMap.get(Constants.Email.NETEASE_SERVICE_BEAN);
            if (service == null) {
                logger.warn("网易邮箱服务不存在");
            }
            return service;
        } else {
            logger.warn("不支持的邮箱类型: email={}", email);
            return null;
        }
    }

    /**
     * 根据邮箱类型获取对应的邮件服务
     * @param mailServiceMap 邮件服务Map
     * @param mailType 邮箱类型（qq或netease）
     * @return 邮件服务，如果未找到返回null
     */
    public static MailService getMailServiceByType(Map<String, MailService> mailServiceMap, String mailType) {
        if (mailServiceMap == null || mailServiceMap.isEmpty()) {
            logger.error("邮件服务Map未初始化");
            return null;
        }

        if (mailType == null || mailType.trim().isEmpty()) {
            logger.warn("邮箱类型为空");
            return null;
        }

        return switch (mailType) {
            case Constants.Email.QQ_SERVICE_BEAN -> mailServiceMap.get(Constants.Email.QQ_SERVICE_BEAN);
            case Constants.Email.NETEASE_SERVICE_BEAN -> mailServiceMap.get(Constants.Email.NETEASE_SERVICE_BEAN);
            default -> {
                logger.warn("不支持的邮箱类型: mailType={}", mailType);
                yield null;
            }
        };
    }

    /**
     * 根据邮箱类型获取邮件服务，如果不存在则返回错误结果
     * @param mailServiceMap 邮件服务Map
     * @param mailType 邮箱类型
     * @return 结果对象，如果服务不存在则返回错误结果，否则返回null（表示服务存在，需要调用者进一步处理）
     */
    public static Result<?> validateAndGetMailService(Map<String, MailService> mailServiceMap, String mailType) {
        if (mailServiceMap == null || mailServiceMap.isEmpty()) {
            logger.error("邮件服务未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "邮件服务未初始化");
        }

        MailService service = getMailServiceByType(mailServiceMap, mailType);
        if (service == null) {
            String serviceName = Constants.Email.QQ_SERVICE_BEAN.equals(mailType) ? "QQ" : 
                                Constants.Email.NETEASE_SERVICE_BEAN.equals(mailType) ? "网易" : "未知";
            logger.warn("{}邮箱服务不存在", serviceName);
            return Result.fail(Code.EMAIL_NOT_SUPPORT, serviceName + "邮箱服务不可用");
        }

        return null; // 表示服务存在
    }
}

