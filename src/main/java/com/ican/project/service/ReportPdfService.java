package com.ican.project.service;

import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.Video;

/**
 * PDF报告生成服务
 */
public interface ReportPdfService {

    /**
     * 生成PDF报告并上传到MinIO
     *
     * @param result 分析结果
     * @param video  视频信息
     * @return MinIO中的存储路径（objectName）
     */
    String generateAndUpload(AnalysisResult result, Video video);
}
