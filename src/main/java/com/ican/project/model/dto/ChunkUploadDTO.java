package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分片上传请求DTO
 */
@Data
@Schema(description = "分片上传请求")
public class ChunkUploadDTO {
    
    @Schema(description = "文件唯一标识（MD5）")
    @NotBlank(message = "文件标识不能为空")
    private String fileIdentifier;
    
    @Schema(description = "原始文件名")
    @NotBlank(message = "文件名不能为空")
    private String fileName;
    
    @Schema(description = "当前分片序号（从0开始）")
    @NotNull(message = "分片序号不能为空")
    @Min(value = 0, message = "分片序号不能小于0")
    private Integer chunkNumber;
    
    @Schema(description = "总分片数")
    @NotNull(message = "总分片数不能为空")
    @Min(value = 1, message = "总分片数不能小于1")
    private Integer totalChunks;
    
    @Schema(description = "当前分片大小")
    @NotNull(message = "分片大小不能为空")
    private Long chunkSize;
    
    @Schema(description = "文件总大小")
    @NotNull(message = "文件总大小不能为空")
    private Long totalSize;
    
    @Schema(description = "视频标题")
    private String title;
    
    @Schema(description = "视频描述")
    private String description;
}

