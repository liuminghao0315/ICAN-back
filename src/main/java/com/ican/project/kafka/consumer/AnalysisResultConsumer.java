package com.ican.project.kafka.consumer;

import com.ican.project.kafka.config.KafkaTopicConstants;
import com.ican.project.kafka.message.AnalysisResultMessage;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.websocket.TaskProgressWebSocket;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分析结果 Kafka 消费者
 * 负责接收算法服务处理完成后的结果并保存
 */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class AnalysisResultConsumer {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisResultConsumer.class);

    @Autowired
    private AnalysisResultService analysisResultService;

    @Autowired
    private AnalysisTaskService analysisTaskService;

    @Autowired
    private TaskProgressWebSocket taskProgressWebSocket;

    /**
     * 监听分析结果 Topic
     *
     * @param record Kafka 消息记录
     * @param ack 确认对象
     */
    @KafkaListener(
            topics = KafkaTopicConstants.ANALYSIS_RESULT_TOPIC,
            groupId = KafkaTopicConstants.VIDEO_ANALYSIS_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAnalysisResult(ConsumerRecord<String, AnalysisResultMessage> record,
                                       Acknowledgment ack) {
        AnalysisResultMessage message = record.value();

        logger.info("收到分析结果: topic={}, partition={}, offset={}, taskId={}, status={}",
                record.topic(), record.partition(), record.offset(),
                message.getTaskId(), message.getStatus());

        try {
            // 1. 保存分析结果
            saveAnalysisResult(message);

            // 2. 更新任务状态
            String taskStatus = "SUCCESS".equals(message.getStatus())
                    ? AnalysisTask.Status.COMPLETED.name()
                    : AnalysisTask.Status.FAILED.name();
            analysisTaskService.updateTaskStatus(message.getTaskId(), taskStatus, 100);

            // 3. 通知前端
            String notifyMessage = "SUCCESS".equals(message.getStatus())
                    ? "分析完成"
                    : "分析失败: " + message.getErrorMessage();
            taskProgressWebSocket.sendProgress(
                    message.getUserId(),
                    message.getTaskId(),
                    taskStatus,
                    100,
                    notifyMessage
            );

            // 4. 确认消息
            ack.acknowledge();
            logger.info("分析结果处理完成: taskId={}", message.getTaskId());

        } catch (Exception e) {
            logger.error("处理分析结果失败: taskId={}, error={}",
                    message.getTaskId(), e.getMessage(), e);
            ack.acknowledge();
        }
    }

    /**
     * 保存分析结果
     *
     * @param message 分析结果消息
     */
    private void saveAnalysisResult(AnalysisResultMessage message) {
        AnalysisResult result = AnalysisResult.builder()
                .taskId(message.getTaskId())
                .videoId(message.getVideoId())
                .riskScore(message.getRiskScore() != null ? BigDecimal.valueOf(message.getRiskScore()) : null)
                .riskLevel(message.getRiskLevel())
                .videoFeatures(message.getVideoFeatures())
                .audioFeatures(message.getAudioFeatures())
                .textFeatures(message.getTextFeatures())
                .universityName(message.getUniversityInfo())
                .topicCategory(message.getTopicCategory())
                .sentimentLabel(message.getSentiment())
                .topicKeywords(message.getKeywords())
                .gmtCreated(LocalDateTime.now())
                .build();

        analysisResultService.saveResult(result);
        logger.info("分析结果已保存: taskId={}, riskScore={}, riskLevel={}",
                message.getTaskId(), message.getRiskScore(), message.getRiskLevel());
    }
}

