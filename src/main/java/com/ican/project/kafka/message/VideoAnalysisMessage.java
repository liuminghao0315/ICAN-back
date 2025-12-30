package com.ican.project.kafka.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 视频分析消息
 * 用于通过 Kafka 发送视频分析任务
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalysisMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 视频ID
     */
    private String videoId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 视频标题
     */
    private String videoTitle;

    /**
     * 视频文件名
     */
    private String fileName;

    /**
     * 视频在 MinIO 中的存储路径
     */
    private String filePath;

    /**
     * 视频访问URL
     */
    private String videoUrl;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件类型（MIME类型）
     */
    private String fileType;

    /**
     * 回调URL（算法服务处理完成后回调）
     */
    private String callbackUrl;

    /**
     * 消息创建时间
     */
    private LocalDateTime createTime;

    /**
     * 优先级（1-10，数字越大优先级越高）
     */
    private Integer priority;

    /**
     * 额外参数（JSON格式）
     */
    private String extraParams;
}

