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
}
