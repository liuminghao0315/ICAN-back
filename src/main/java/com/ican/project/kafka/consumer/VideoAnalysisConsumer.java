package com.ican.project.kafka.consumer;

import com.ican.project.kafka.config.KafkaTopicConstants;
import com.ican.project.kafka.message.VideoAnalysisMessage;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.MockAlgorithmService;
import com.ican.project.websocket.TaskProgressWebSocket;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 视频分析任务 Kafka 消费者
 * 负责从 Kafka 消息队列中消费视频分析任务并处理
 */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class VideoAnalysisConsumer {

    private static final Logger logger = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    @Autowired
    private AnalysisTaskService analysisTaskService;

    @Autowired
    private MockAlgorithmService mockAlgorithmService;

    @Autowired
    private TaskProgressWebSocket taskProgressWebSocket;

    /**
     * 监听视频分析任务 Topic
     * 使用手动确认模式，确保消息处理完成后才提交偏移量
     *
     * @param record Kafka 消息记录
     * @param ack 确认对象
     */
    @KafkaListener(
            topics = KafkaTopicConstants.VIDEO_ANALYSIS_TOPIC,
            groupId = KafkaTopicConstants.VIDEO_ANALYSIS_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeVideoAnalysisTask(ConsumerRecord<String, VideoAnalysisMessage> record,
                                          Acknowledgment ack) {
        VideoAnalysisMessage message = record.value();
        
        logger.info("收到视频分析任务: topic={}, partition={}, offset={}, videoId={}, taskId={}",
                record.topic(), record.partition(), record.offset(),
                message.getVideoId(), message.getTaskId());

        try {
            // 1. 更新任务状态为处理中
            updateTaskStatus(message.getTaskId(), AnalysisTask.Status.PROCESSING.name(), 10);

            // 2. 通过 WebSocket 通知前端任务开始处理
            notifyProgress(message.getUserId(), message.getTaskId(), "PROCESSING", 10, "开始处理视频");

            // 3. 调用算法服务进行分析（当前使用Mock服务）
            processVideoAnalysis(message);

            // 4. 手动确认消息已处理
            ack.acknowledge();
            logger.info("视频分析任务处理完成并确认: videoId={}, taskId={}",
                    message.getVideoId(), message.getTaskId());

        } catch (Exception e) {
            logger.error("处理视频分析任务失败: videoId={}, taskId={}, error={}",
                    message.getVideoId(), message.getTaskId(), e.getMessage(), e);

            // 更新任务状态为失败
            updateTaskStatus(message.getTaskId(), AnalysisTask.Status.FAILED.name(), 0);
            notifyProgress(message.getUserId(), message.getTaskId(), "FAILED", 0, "处理失败: " + e.getMessage());

            // 仍然确认消息（避免消息重复消费导致死循环）
            // 如果需要重试，可以将消息发送到死信队列
            ack.acknowledge();
        }
    }

    /**
     * 处理视频分析
     * 直接调用 Mock 算法服务的异步方法处理
     * Mock 服务会自己处理进度通知和结果保存
     *
     * @param message 视频分析消息
     */
    private void processVideoAnalysis(VideoAnalysisMessage message) {
        // 直接调用 Mock 算法服务进行异步分析
        // Mock 服务内部会处理进度更新和结果保存
        mockAlgorithmService.analyzeVideoAsync(message.getTaskId(), message.getVideoId(), message.getFilePath());
        
        logger.info("已触发异步分析: taskId={}, videoId={}", message.getTaskId(), message.getVideoId());
    }

    /**
     * 更新任务状态
     *
     * @param taskId 任务ID
     * @param status 状态
     * @param progress 进度
     */
    private void updateTaskStatus(String taskId, String status, Integer progress) {
        try {
            analysisTaskService.updateTaskStatus(taskId, status, progress);
        } catch (Exception e) {
            logger.error("更新任务状态失败: taskId={}, status={}, error={}",
                    taskId, status, e.getMessage());
        }
    }

    /**
     * 通过 WebSocket 通知进度
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param status 状态
     * @param progress 进度
     * @param message 消息
     */
    private void notifyProgress(String userId, String taskId, String status,
                                 Integer progress, String message) {
        try {
            taskProgressWebSocket.sendProgress(userId, taskId, status, progress, message);
        } catch (Exception e) {
            logger.warn("发送WebSocket通知失败: userId={}, taskId={}, error={}",
                    userId, taskId, e.getMessage());
        }
    }
}

