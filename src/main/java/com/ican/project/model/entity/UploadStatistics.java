package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 上传统计实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("upload_statistics")
public class UploadStatistics {
    
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 统计日期
     */
    private LocalDate statDate;
    
    /**
     * 上传数量
     */
    private Integer uploadCount;
    
    /**
     * 总上传大小（字节）
     */
    private Long totalSize;
    
    /**
     * 创建时间
     */
    private LocalDateTime gmtCreated;
    
    /**
     * 修改时间
     */
    private LocalDateTime gmtModified;
}

