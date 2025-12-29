package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建分析任务请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "创建分析任务请求")
public class AnalysisTaskDTO {
    
    @NotBlank(message = "视频ID不能为空")
    @Schema(description = "视频ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String videoId;
    
    @Schema(description = "任务类型: FULL_ANALYSIS/VIDEO_ONLY/AUDIO_ONLY/TEXT_ONLY，默认FULL_ANALYSIS")
    private String taskType;
    
    @Schema(description = "是否强制重新分析（取消已有的进行中任务）")
    private Boolean forceRestart;
    
    /**
     * 去除空格
     */
    public AnalysisTaskDTO trimMe() {
        if (videoId != null) {
            videoId = videoId.trim();
        }
        if (taskType != null) {
            taskType = taskType.trim().toUpperCase();
        }
        return this;
    }
}

