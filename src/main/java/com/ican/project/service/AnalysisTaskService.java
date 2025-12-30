package com.ican.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.dto.AnalysisTaskDTO;
import com.ican.project.model.vo.AnalysisTaskVO;

/**
 * 分析任务服务接口
 */
public interface AnalysisTaskService {
    
    /**
     * 创建分析任务
     * @param dto 任务信息
     * @param userId 用户ID
     * @return 任务VO
     */
    AnalysisTaskVO createTask(AnalysisTaskDTO dto, String userId);
    
    /**
     * 获取任务详情
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 任务VO
     */
    AnalysisTaskVO getTaskById(String taskId, String userId);
    
    /**
     * 获取视频的最新任务
     * @param videoId 视频ID
     * @param userId 用户ID
     * @return 任务VO
     */
    AnalysisTaskVO getLatestTaskByVideoId(String videoId, String userId);
    
    /**
     * 获取用户任务列表
     * @param userId 用户ID
     * @param status 状态筛选（可选）
     * @param riskLevel 风险等级筛选（可选）: LOW/MEDIUM/HIGH
     * @param page 页码
     * @param size 每页数量
     * @param sortBy 排序字段（gmtCreated, riskScore, videoDuration）
     * @param sortOrder 排序方向（asc, desc）
     * @return 任务列表
     */
    Page<AnalysisTaskVO> getUserTasks(String userId, String status, String riskLevel, int page, int size, String sortBy, String sortOrder);
    
    /**
     * 取消任务
     * @param taskId 任务ID
     * @param userId 用户ID
     */
    void cancelTask(String taskId, String userId);
    
    /**
     * 重新执行任务
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 新任务VO
     */
    AnalysisTaskVO retryTask(String taskId, String userId);
    
    /**
     * 更新任务状态（内部调用）
     * @param taskId 任务ID
     * @param status 状态
     * @param progress 进度
     * @param errorMessage 错误信息
     */
    void updateTaskStatus(String taskId, String status, Integer progress, String errorMessage);
    
    /**
     * 标记任务开始处理（内部调用）
     * @param taskId 任务ID
     */
    void markTaskProcessing(String taskId);
    
    /**
     * 标记任务完成（内部调用）
     * @param taskId 任务ID
     */
    void markTaskCompleted(String taskId);
    
    /**
     * 标记任务失败（内部调用）
     * @param taskId 任务ID
     * @param errorMessage 错误信息
     */
    void markTaskFailed(String taskId, String errorMessage);
    
    /**
     * 获取待处理的任务（供Mock算法服务调用）
     * @return 任务VO
     */
    AnalysisTaskVO getPendingTask();
}

