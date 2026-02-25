package com.ican.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.vo.AnalysisTaskVO;

/**
 * 用户收藏服务接口
 */
public interface FavoriteService {

    /**
     * 收藏一个分析任务
     *
     * @param userId 当前用户ID
     * @param taskId 分析任务ID
     */
    void addFavorite(String userId, String taskId);

    /**
     * 取消收藏
     *
     * @param userId 当前用户ID
     * @param taskId 分析任务ID
     */
    void removeFavorite(String userId, String taskId);

    /**
     * 分页查询收藏列表（按收藏时间倒序）
     *
     * @param userId     当前用户ID
     * @param page       页码（从1开始）
     * @param size       每页条数
     * @param riskLevel  风险等级筛选（可选）
     * @param keyword    关键词搜索（可选）
     * @param sourceType 来源类型筛选（可选）：LOCAL_UPLOAD / URL_IMPORT
     * @param sortField  排序字段：gmtCreated / riskScore
     * @param sortDir    排序方向：asc / desc
     */
    Page<AnalysisTaskVO> getFavoriteList(String userId, int page, int size,
                                         String riskLevel, String keyword,
                                         String sourceType,
                                         String sortField, String sortDir);
}
