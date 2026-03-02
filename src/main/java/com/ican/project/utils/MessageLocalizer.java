package com.ican.project.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 将后端透传给前端的纯英文错误文案转换为中文。
 * 仅处理“整句英文/ASCII”消息，含中文的消息保持原样。
 */
public final class MessageLocalizer {

    private static final Pattern HAS_CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern HAS_ENGLISH_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern ASCII_MESSAGE = Pattern.compile("^[\\x20-\\x7E\\r\\n\\t]+$");

    private MessageLocalizer() {
    }

    public static String localizeError(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        // 已经包含中文，不做处理
        if (HAS_CHINESE.matcher(message).find()) {
            return message;
        }

        // 不是英文句子（例如纯数字/特殊符号），不做处理
        if (!HAS_ENGLISH_LETTER.matcher(message).find()) {
            return message;
        }

        final String normalized = message.toLowerCase(Locale.ROOT);

        if (normalized.contains("file name too long")) {
            return "文件名过长";
        }
        if (normalized.contains("no such file or directory")) {
            return "文件不存在或路径无效";
        }
        if (normalized.contains("permission denied") || normalized.contains("access denied")) {
            return "权限不足，拒绝访问";
        }
        if (normalized.contains("network error")) {
            return "网络错误，请检查网络连接";
        }
        if (normalized.contains("connection refused")) {
            return "无法连接到服务器，请稍后重试";
        }
        if (normalized.contains("request timeout") || normalized.contains("timed out")) {
            return "请求超时，请稍后重试";
        }
        if (normalized.contains("internal server error")) {
            return "服务器内部错误，请稍后重试";
        }
        if (normalized.contains("not found")) {
            return "请求的资源不存在";
        }
        if (normalized.contains("unauthorized")) {
            return "未授权访问，请先登录";
        }
        if (normalized.contains("forbidden")) {
            return "权限不足，拒绝访问";
        }
        if (normalized.contains("bad credentials")) {
            return "用户名或密码错误";
        }
        if (normalized.contains("invalid argument")) {
            return "参数不合法";
        }
        if (normalized.contains("null pointer")) {
            return "系统内部错误，请稍后重试";
        }

        // 仅对纯 ASCII 英文消息做兜底翻译，避免误改其他语言
        if (ASCII_MESSAGE.matcher(message).matches()) {
            return "请求处理失败，请稍后重试";
        }

        return message;
    }
}
