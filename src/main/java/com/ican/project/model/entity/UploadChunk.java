package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分片上传记录实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("upload_chunk")
public class UploadChunk {
    
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    
    /**
     * 文件唯一标识（MD5）
     */
    private String fileIdentifier;
    
    /**
     * 上传用户ID
     */
    private String userId;
    
    /**
     * 原始文件名
     */
    private String fileName;
    
    /**
     * 总分片数
     */
    private Integer totalChunks;
    
    /**
     * 当前分片序号
     */
    private Integer chunkNumber;
    
    /**
     * 分片大小
     */
    private Long chunkSize;
    
    /**
     * 文件总大小
     */
    private Long totalSize;
    
    /**
     * 分片临时存储路径
     */
    private String chunkPath;
    
    /**
     * 是否已上传
     */
    private Boolean isUploaded;
    
    private LocalDateTime gmtCreated;
}

