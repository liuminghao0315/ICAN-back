package com.ican.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分片上传响应VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分片上传响应")
public class ChunkUploadVO {
    
    @Schema(description = "是否需要上传（false表示秒传成功）")
    private Boolean needUpload;
    
    @Schema(description = "已上传的分片序号列表")
    private List<Integer> uploadedChunks;
    
    @Schema(description = "是否全部上传完成")
    private Boolean finished;
    
    @Schema(description = "视频ID（上传完成后返回）")
    private String videoId;
    
    @Schema(description = "视频URL（上传完成后返回）")
    private String videoUrl;
    
    @Schema(description = "提示消息")
    private String message;
}

