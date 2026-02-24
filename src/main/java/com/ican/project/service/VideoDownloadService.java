package com.ican.project.service;

/**
 * URL视频下载服务接口
 * 负责从外部URL下载视频资源
 */
public interface VideoDownloadService {
    
    /**
     * 异步下载外部视频
     * @param url 视频URL
     * @param videoId 视频记录ID
     * @param taskId 关联的分析任务ID
     * @param userId 用户ID
     */
    void downloadVideoAsync(String url, String videoId, String taskId, String userId);

    /**
     * 预校验 URL 是否可被 yt-dlp 解析（同步，用于提交前拦截）
     * @param url 视频URL
     * @return 视频标题（校验通过），null 表示无法解析
     */
    String validateUrl(String url);

    /**
     * 结构化 URL 校验结果
     */
    record UrlValidateResult(
        /** 校验是否通过 */
        boolean valid,
        /** 视频标题（valid=true 时有值） */
        String title,
        /**
         * 错误类型（valid=false 时有值）：
         *   INVALID_URL       - 链接格式非法或无法访问
         *   UNSUPPORTED       - 平台不受支持
         *   PLATFORM_RESTRICTED - 平台有地区/防盗链限制（403）
         *   LOGIN_REQUIRED    - 需要登录 Cookie 才能访问
         */
        String errorType,
        /** 用户友好的错误描述 */
        String errorMessage
    ) {}

    /**
     * 结构化 URL 校验（返回详细错误类型，供前端分级展示）
     */
    UrlValidateResult validateUrlStructured(String url);

    /**
     * 保存 Cookies 文本到 cookies.txt 文件
     * @param cookiesContent Netscape 格式的 cookies 文本
     */
    void saveCookies(String cookiesContent) throws java.io.IOException;
}
