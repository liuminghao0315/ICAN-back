package com.ican.project.service;

import java.util.List;
import java.util.Map;

/**
 * 上传统计服务接口
 */
public interface UploadStatisticsService {
    
    /**
     * 记录一次上传
     * @param userId 用户ID
     * @param fileSize 文件大小
     */
    void recordUpload(String userId, Long fileSize);
    
    /**
     * 获取用户某段时间的上传统计
     * @param userId 用户ID
     * @param days 天数（最近N天）
     * @return 统计数据列表
     */
    List<Map<String, Object>> getUploadTrend(String userId, int days);
    
    /**
     * 获取用户的总上传统计
     * @param userId 用户ID
     * @return 总统计数据
     */
    Map<String, Object> getTotalStats(String userId);
}

