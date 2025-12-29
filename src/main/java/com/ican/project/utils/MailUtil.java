package com.ican.project.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

@Component
public class MailUtil {
    private static final Logger logger = LoggerFactory.getLogger(MailUtil.class);
    private static final String SSL_FACTORY = "javax.net.ssl.SSLSocketFactory";
    private static final String SMTP_PORT = "465";
    private static final String CONTENT_TYPE = "text/html;charset=UTF-8";
    private static final String SMTP_AUTH = "true";
    private static final String SOCKET_FACTORY_FALLBACK = "false";

    @Value("${qqMail.sender}")
    private String qqSender;

    @Value("${qqMail.smtpCode}")
    private String qqSmtpCode;

    @Value("${netease.sender}")
    private String neteaseSender;

    @Value("${netease.smtpCode}")
    private String neteaseSmtpCode;

    /**
     * 发送QQ邮箱邮件
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件内容
     * @throws MessagingException 邮件发送异常
     */
    public void sendQQMail(String to, String subject, String content) throws MessagingException {
        sendMail("smtp.qq.com", qqSender, qqSmtpCode, to, subject, content);
    }

    /**
     * 发送网易邮箱邮件
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件内容
     * @throws MessagingException 邮件发送异常
     */
    public void sendNetEaseMail(String to, String subject, String content) throws MessagingException {
        sendMail("smtp.163.com", neteaseSender, neteaseSmtpCode, to, subject, content);
    }

    /**
     * 通用邮件发送方法
     * @param smtpHost SMTP服务器地址
     * @param from 发件人邮箱
     * @param password 发件人授权码
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件内容
     * @throws MessagingException 邮件发送异常
     */
    private void sendMail(String smtpHost, String from, String password, String to, String subject, String content) throws MessagingException {
        try {
            // 配置邮箱信息
            Properties props = new Properties();
            props.setProperty("mail.smtp.host", smtpHost);
            props.setProperty("mail.smtp.port", SMTP_PORT);
            props.setProperty("mail.smtp.auth", SMTP_AUTH);
            
            // SSL/TLS 配置
            props.setProperty("mail.smtp.ssl.enable", "true");
            props.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");
            props.setProperty("mail.smtp.socketFactory.class", SSL_FACTORY);
            props.setProperty("mail.smtp.socketFactory.port", SMTP_PORT);
            props.setProperty("mail.smtp.socketFactory.fallback", SOCKET_FACTORY_FALLBACK);
            props.setProperty("mail.smtp.ssl.trust", smtpHost);
            
            // 超时设置
            props.setProperty("mail.smtp.connectiontimeout", "10000");
            props.setProperty("mail.smtp.timeout", "10000");
            props.setProperty("mail.smtp.writetimeout", "10000");
            
            // 显式设置发件人地址，确保与认证用户一致（163邮箱要求）
            props.setProperty("mail.smtp.from", from);

            // 建立邮件会话 - 使用 getInstance 而非 getDefaultInstance，避免 Session 缓存问题
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(from, password);
                }
            });
            
            // 开启调试模式（生产环境可关闭）
            // session.setDebug(true);

            // 建立邮件对象
            MimeMessage message = new MimeMessage(session);
            // 设置发件人地址（必须与认证用户完全一致，即from参数的值）
            InternetAddress fromAddress = new InternetAddress(from);
            message.setFrom(fromAddress);
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setContent(content, CONTENT_TYPE);
            message.saveChanges();

            // 发送邮件
            Transport.send(message);
            logger.info("邮件发送成功: to={}, subject={}", to, subject);
        } catch (MessagingException e) {
            logger.error("邮件发送失败: to={}, subject={}, host={}", to, subject, smtpHost, e);
            throw e;
        } catch (Exception e) {
            logger.error("邮件发送异常: to={}, subject={}, host={}", to, subject, smtpHost, e);
            throw new MessagingException("邮件发送异常: " + e.getMessage(), e);
        }
    }
}
