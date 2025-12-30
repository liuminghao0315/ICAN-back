package com.ican.project.kafka.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 分析结果消息
 * 用于接收算法服务处理完成后的结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResultMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 视频ID
     */
    private String videoId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 处理状态：SUCCESS, FAILED, TIMEOUT
     */
    private String status;

    /**
     * 风险评分（0.0 - 1.0）
     */
    private Double riskScore;

    /**
     * 风险等级：LOW, MEDIUM, HIGH
     */
    private String riskLevel;

    /**
     * 视频特征分析结果（JSON格式）
     */
    private String videoFeatures;

    /**
     * 音频特征分析结果（JSON格式）
     */
    private String audioFeatures;

    /**
     * 文本特征分析结果（JSON格式）
     */
    private String textFeatures;

    /**
     * 识别的高校信息
     */
    private String universityInfo;

    /**
     * 主题分类
     */
    private String topicCategory;

    /**
     * 情感倾向：POSITIVE, NEUTRAL, NEGATIVE
     */
    private String sentiment;

    /**
     * 关键词列表（JSON数组）
     */
    private String keywords;

    /**
     * 处理耗时（毫秒）
     */
    private Long processingTime;

    /**
     * 错误信息（如果处理失败）
     */
    private String errorMessage;

    /**
     * 消息创建时间
     */
    private LocalDateTime createTime;

    /**
     * 扩展数据
     */
    private Map<String, Object> extendData;
}

