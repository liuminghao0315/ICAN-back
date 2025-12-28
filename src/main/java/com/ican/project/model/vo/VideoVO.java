package com.ican.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 视频信息VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "视频信息")
public class VideoVO {
    
    @Schema(description = "视频ID")
    private String id;
    
    @Schema(description = "视频标题")
    private String title;
    
    @Schema(description = "视频描述")
    private String description;
    
    @Schema(description = "原始文件名")
    private String fileName;
    
    @Schema(description = "文件大小（字节）")
    private Long fileSize;
    
    @Schema(description = "文件类型")
    private String fileType;
    
    @Schema(description = "视频时长（秒）")
    private Double duration;
    
    @Schema(description = "视频宽度")
    private Integer width;
    
    @Schema(description = "视频高度")
    private Integer height;
    
    @Schema(description = "缩略图URL")
    private String thumbnailUrl;
    
    @Schema(description = "视频URL")
    private String videoUrl;
    
    @Schema(description = "状态")
    private String status;
    
    @Schema(description = "创建时间")
    private LocalDateTime gmtCreated;
}

