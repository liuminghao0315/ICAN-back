package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 算法服务回调DTO
 * 用于接收真实算法服务的分析结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "算法服务回调请求")
public class AlgorithmCallbackDTO {
    
    @NotBlank(message = "任务ID不能为空")
    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;
    
    @NotBlank(message = "状态不能为空")
    @Schema(description = "处理状态: completed/failed", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;
    
    @Schema(description = "错误信息（失败时提供）")
    private String errorMessage;
    
    // ========== 以下为分析结果（成功时提供） ==========
    
    @Schema(description = "综合风险评分(0-1)")
    private BigDecimal riskScore;
    
    @Schema(description = "风险等级: LOW/MEDIUM/HIGH")
    private String riskLevel;
    
    @Schema(description = "是否高校相关")
    private Boolean isUniversityRelated;
    
    @Schema(description = "识别到的高校名称")
    private String universityName;
    
    @Schema(description = "高校识别置信度")
    private BigDecimal universityConfidence;
    
    @Schema(description = "主题分类")
    private String topicCategory;
    
    @Schema(description = "主题关键词列表")
    private List<String> topicKeywords;
    
    @Schema(description = "情感评分(-1到1)")
    private BigDecimal sentimentScore;
    
    @Schema(description = "情感标签: POSITIVE/NEUTRAL/NEGATIVE")
    private String sentimentLabel;
    
    @Schema(description = "视频特征")
    private Map<String, Object> videoFeatures;
    
    @Schema(description = "音频特征")
    private Map<String, Object> audioFeatures;
    
    @Schema(description = "语音转文字结果")
    private String transcription;
    
    @Schema(description = "文本特征")
    private Map<String, Object> textFeatures;
    
    @Schema(description = "受众分析结果")
    private Map<String, Object> audienceAnalysis;
    
    @Schema(description = "传播潜力评分(0-1)")
    private BigDecimal spreadPotential;
    
    /**
     * 去除空格
     */
    public AlgorithmCallbackDTO trimMe() {
        if (taskId != null) {
            taskId = taskId.trim();
        }
        if (status != null) {
            status = status.trim().toLowerCase();
        }
        if (riskLevel != null) {
            riskLevel = riskLevel.trim().toUpperCase();
        }
        if (sentimentLabel != null) {
            sentimentLabel = sentimentLabel.trim().toUpperCase();
        }
        return this;
    }
}

