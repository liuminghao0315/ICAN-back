package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 视频上传请求DTO
 */
@Data
@Schema(description = "视频上传请求")
public class VideoUploadDTO {
    
    @Schema(description = "视频标题")
    @NotBlank(message = "视频标题不能为空")
    private String title;
    
    @Schema(description = "视频描述")
    private String description;
}

