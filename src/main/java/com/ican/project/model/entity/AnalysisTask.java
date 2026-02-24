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
 * 分析任务实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("analysis_task")
public class AnalysisTask {
    
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    
    /**
     * 关联视频ID
     */
    private String videoId;
    
    /**
     * 创建用户ID
     */
    private String userId;
    
    /**
     * 任务类型
     */
    private String taskType;
    
    /**
     * 任务状态
     */
    private String status;
    
    /**
     * 处理进度(0-100)
     */
    private Integer progress;
    
    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 失败类型：DOWNLOAD_FAILED（下载失败，无文件）/ ANALYSIS_FAILED（分析失败，有文件）
     * 仅 status=FAILED 时有意义
     */
    private String failureType;
    
    /**
     * 开始处理时间
     */
    private LocalDateTime startedAt;
    
    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
    
    private LocalDateTime gmtCreated;
    
    private LocalDateTime gmtModified;
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        FULL_ANALYSIS,  // 完整分析
        VIDEO_ONLY,     // 仅视频分析
        AUDIO_ONLY,     // 仅音频分析
        TEXT_ONLY       // 仅文本分析
    }
    
    /**
     * 任务状态枚举
     */
    public enum Status {
        DOWNLOADING, // 下载中（URL导入场景）
        PENDING,     // 等待中
        PROCESSING,  // 处理中
        COMPLETED,   // 已完成
        FAILED,      // 失败
        CANCELLED    // 已取消
    }
}

