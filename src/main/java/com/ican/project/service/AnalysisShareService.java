package com.ican.project.service;

import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.model.vo.AnalysisShareVO;

public interface AnalysisShareService {

    /**
     * 为指定分析结果创建分享链接。
     */
    AnalysisShareVO createShare(String resultId, String userId);

    /**
     * 根据分享 token 获取公开可读的分析结果。
     */
    AnalysisResultVO getSharedResult(String token);
}