package com.ican.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分析结果VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分析结果信息")
public class AnalysisResultVO {
    
    @Schema(description = "结果ID")
    private String id;
    
    @Schema(description = "任务ID")
    private String taskId;
    
    @Schema(description = "视频ID")
    private String videoId;
    
    @Schema(description = "视频标题")
    private String videoTitle;
    
    @Schema(description = "视频描述")
    private String videoDescription;
    
    @Schema(description = "视频URL")
    private String videoUrl;
    
    // ========== 综合风险评估 ==========
    
    @Schema(description = "综合风险评分(0-1)")
    private BigDecimal riskScore;
    
    @Schema(description = "风险等级: LOW/MEDIUM/HIGH")
    private String riskLevel;
    
    @Schema(description = "风险等级描述")
    private String riskLevelDesc;
    
    // ========== 高校身份识别 ==========
    
    @Schema(description = "是否高校相关")
    private Boolean isUniversityRelated;
    
    @Schema(description = "识别到的高校名称")
    private String universityName;
    
    @Schema(description = "高校识别置信度")
    private BigDecimal universityConfidence;
    
    // ========== 内容主题 ==========
    
    @Schema(description = "主题分类")
    private String topicCategory;
    
    @Schema(description = "主题关键词列表")
    private List<String> topicKeywords;
    
    // ========== 情感分析 ==========
    
    @Schema(description = "情感评分(-1到1)")
    private BigDecimal sentimentScore;
    
    @Schema(description = "情感标签: POSITIVE/NEUTRAL/NEGATIVE")
    private String sentimentLabel;
    
    @Schema(description = "情感标签描述")
    private String sentimentLabelDesc;
    
    // ========== 多模态特征 ==========
    
    @Schema(description = "视频特征")
    private Map<String, Object> videoFeatures;
    
    @Schema(description = "音频特征")
    private Map<String, Object> audioFeatures;
    
    @Schema(description = "语音转文字结果")
    private String transcription;
    
    @Schema(description = "文本特征")
    private Map<String, Object> textFeatures;
    
    // ========== 受众与传播 ==========
    
    @Schema(description = "受众分析结果")
    private Map<String, Object> audienceAnalysis;
    
    @Schema(description = "传播潜力评分(0-1)")
    private BigDecimal spreadPotential;
    
    @Schema(description = "创建时间")
    private LocalDateTime gmtCreated;
    
    /**
     * 获取风险等级描述
     */
    public static String getRiskLevelDescription(String riskLevel) {
        if (riskLevel == null) return "未知";
        return switch (riskLevel.toUpperCase()) {
            case "LOW" -> "低风险";
            case "MEDIUM" -> "中风险";
            case "HIGH" -> "高风险";
            default -> "未知";
        };
    }
    
    /**
     * 获取情感标签描述
     */
    public static String getSentimentLabelDescription(String sentimentLabel) {
        if (sentimentLabel == null) return "未知";
        return switch (sentimentLabel.toUpperCase()) {
            case "POSITIVE" -> "正面";
            case "NEUTRAL" -> "中性";
            case "NEGATIVE" -> "负面";
            default -> "未知";
        };
    }
}

