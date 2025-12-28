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
 * 视频实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video")
public class Video {
    
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    
    /**
     * 上传用户ID
     */
    private String userId;
    
    /**
     * 视频标题
     */
    private String title;
    
    /**
     * 视频描述
     */
    private String description;
    
    /**
     * 原始文件名
     */
    private String fileName;
    
    /**
     * MinIO存储路径
     */
    private String filePath;
    
    /**
     * 文件大小（字节）
     */
    private Long fileSize;
    
    /**
     * 文件类型
     */
    private String fileType;
    
    /**
     * 视频时长（秒）
     */
    private Double duration;
    
    /**
     * 视频宽度
     */
    private Integer width;
    
    /**
     * 视频高度
     */
    private Integer height;
    
    /**
     * 缩略图路径
     */
    private String thumbnailPath;
    
    /**
     * 状态: UPLOADED/ANALYZING/COMPLETED/FAILED
     */
    private String status;
    
    private LocalDateTime gmtCreated;
    
    private LocalDateTime gmtModified;
    
    /**
     * 视频状态枚举
     */
    public enum Status {
        UPLOADED,    // 已上传
        ANALYZING,   // 分析中
        COMPLETED,   // 已完成
        FAILED       // 失败
    }
}

