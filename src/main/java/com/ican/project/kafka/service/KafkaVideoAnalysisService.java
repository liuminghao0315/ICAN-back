package com.ican.project.kafka.service;

import cn.hutool.core.util.IdUtil;
import com.ican.project.kafka.message.VideoAnalysisMessage;
import com.ican.project.kafka.producer.VideoAnalysisProducer;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.MinioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Kafka 视频分析服务
 * 封装视频分析任务的创建和发送逻辑
 * 当 kafka.enabled=false 时不注册此服务
 */
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaVideoAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaVideoAnalysisService.class);

    @Autowired
    private VideoAnalysisProducer videoAnalysisProducer;

    @Autowired
    @Lazy
    private AnalysisTaskService analysisTaskService;

    @Autowired
    private MinioService minioService;

    @Value("${server.port:8080}")
    private String serverPort;

    /**
     * 提交视频分析任务
     * 1. 创建分析任务记录
     * 2. 发送 Kafka 消息
     *
     * @param video 视频实体
     * @param userId 用户ID
     * @return 任务ID
     */
    public String submitVideoAnalysisTask(Video video, String userId) {
        // 1. 创建分析任务
        String taskId = IdUtil.fastSimpleUUID();
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .videoId(video.getId())
                .userId(userId)
                .taskType(AnalysisTask.TaskType.FULL_ANALYSIS.name())
                .status(AnalysisTask.Status.PENDING.name())
                .progress(0)
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        analysisTaskService.createTask(task);

        logger.info("创建分析任务: taskId={}, videoId={}, userId={}", taskId, video.getId(), userId);

        // 2. 构建 Kafka 消息
        VideoAnalysisMessage message = VideoAnalysisMessage.builder()
                .taskId(taskId)
                .videoId(video.getId())
                .userId(userId)
                .videoTitle(video.getTitle())
                .fileName(video.getFileName())
                .filePath(video.getFilePath())
                .videoUrl(minioService.getFileUrl(video.getFilePath()))
                .fileSize(video.getFileSize())
                .fileType(video.getFileType())
                .callbackUrl(buildCallbackUrl())
                .createTime(LocalDateTime.now())
                .priority(5) // 默认优先级
                .build();

        // 3. 发送到 Kafka
        videoAnalysisProducer.sendVideoAnalysisTask(message);

        logger.info("视频分析任务已发送到Kafka: taskId={}, videoId={}", taskId, video.getId());

        return taskId;
    }

    /**
     * 提交高优先级视频分析任务
     *
     * @param video 视频实体
     * @param userId 用户ID
     * @param priority 优先级 (1-10)
     * @return 任务ID
     */
    public String submitHighPriorityTask(Video video, String userId, int priority) {
        // 1. 创建分析任务
        String taskId = IdUtil.fastSimpleUUID();
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .videoId(video.getId())
                .userId(userId)
                .taskType(AnalysisTask.TaskType.FULL_ANALYSIS.name())
                .status(AnalysisTask.Status.PENDING.name())
                .progress(0)
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        analysisTaskService.createTask(task);

        // 2. 构建高优先级消息
        VideoAnalysisMessage message = VideoAnalysisMessage.builder()
                .taskId(taskId)
                .videoId(video.getId())
                .userId(userId)
                .videoTitle(video.getTitle())
                .fileName(video.getFileName())
                .filePath(video.getFilePath())
                .videoUrl(minioService.getFileUrl(video.getFilePath()))
                .fileSize(video.getFileSize())
                .fileType(video.getFileType())
                .callbackUrl(buildCallbackUrl())
                .createTime(LocalDateTime.now())
                .priority(priority)
                .build();

        // 3. 发送到指定分区（高优先级发送到分区0）
        Integer partition = priority >= 8 ? 0 : null;
        videoAnalysisProducer.sendVideoAnalysisTaskWithPriority(message, partition);

        logger.info("高优先级任务已发送: taskId={}, priority={}, partition={}",
                taskId, priority, partition);

        return taskId;
    }

    /**
     * 构建回调URL
     */
    private String buildCallbackUrl() {
        // 在生产环境中，这应该是外部可访问的URL
        return "http://localhost:" + serverPort + "/api/algorithm/callback";
    }
}

