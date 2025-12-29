package com.ican.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.vo.AnalysisResultVO;

import java.util.Map;

/**
 * 分析结果服务接口
 */
public interface AnalysisResultService {
    
    /**
     * 保存分析结果（内部调用，由Mock算法服务或真实算法服务调用）
     * @param result 分析结果实体
     * @return 结果ID
     */
    String saveResult(AnalysisResult result);
    
    /**
     * 根据ID获取分析结果
     * @param resultId 结果ID
     * @param userId 用户ID
     * @return 分析结果VO
     */
    AnalysisResultVO getResultById(String resultId, String userId);
    
    /**
     * 根据任务ID获取分析结果
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 分析结果VO
     */
    AnalysisResultVO getResultByTaskId(String taskId, String userId);
    
    /**
     * 根据视频ID获取最新分析结果
     * @param videoId 视频ID
     * @param userId 用户ID
     * @return 分析结果VO
     */
    AnalysisResultVO getLatestResultByVideoId(String videoId, String userId);
    
    /**
     * 获取用户的分析结果列表
     * @param userId 用户ID
     * @param riskLevel 风险等级筛选（可选）
     * @param page 页码
     * @param size 每页数量
     * @return 分析结果列表
     */
    Page<AnalysisResultVO> getUserResults(String userId, String riskLevel, int page, int size);
    
    /**
     * 获取用户分析统计数据
     * @param userId 用户ID
     * @return 统计数据
     */
    Map<String, Object> getUserAnalysisStats(String userId);
    
    /**
     * 获取风险分布统计
     * @param userId 用户ID
     * @return 风险分布
     */
    Map<String, Long> getRiskDistribution(String userId);
    
    /**
     * 删除分析结果
     * @param resultId 结果ID
     * @param userId 用户ID
     */
    void deleteResult(String resultId, String userId);
}

