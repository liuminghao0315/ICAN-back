package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * URL导入分析任务请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "URL导入分析任务请求")
public class UrlImportTaskDTO {
    
    @NotBlank(message = "视频URL不能为空")
    @Schema(description = "视频URL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String url;
    
    @Schema(description = "视频标题（可选，不填则自动提取）")
    private String title;
    
    @Schema(description = "任务类型: FULL_ANALYSIS/VIDEO_ONLY/AUDIO_ONLY/TEXT_ONLY，默认FULL_ANALYSIS")
    private String taskType;

    @Schema(description = "目标文件夹ID（可选，不填则归入未分类）")
    private String folderId;
    
    /**
     * 去除空格
     */
    public UrlImportTaskDTO trimMe() {
        if (url != null) {
            url = url.trim();
        }
        if (title != null) {
            title = title.trim();
        }
        if (taskType != null) {
            taskType = taskType.trim().toUpperCase();
        }
        return this;
    }
}
