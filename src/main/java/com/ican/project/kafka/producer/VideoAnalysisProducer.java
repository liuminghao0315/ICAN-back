package com.ican.project.kafka.producer;

import com.ican.project.kafka.config.KafkaTopicConstants;
import com.ican.project.kafka.message.VideoAnalysisMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 视频分析任务 Kafka 生产者
 * 负责将视频分析任务发送到 Kafka 消息队列
 */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class VideoAnalysisProducer {

    private static final Logger logger = LoggerFactory.getLogger(VideoAnalysisProducer.class);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 发送视频分析任务到 Kafka
     *
     * @param message 视频分析消息
     */
    public void sendVideoAnalysisTask(VideoAnalysisMessage message) {
        String topic = KafkaTopicConstants.VIDEO_ANALYSIS_TOPIC;
        String key = message.getVideoId(); // 使用视频ID作为分区键，确保同一视频的消息发送到同一分区

        logger.info("发送视频分析任务到Kafka: topic={}, videoId={}, taskId={}",
                topic, message.getVideoId(), message.getTaskId());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(topic, key, message);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                logger.info("视频分析任务发送成功: topic={}, partition={}, offset={}, videoId={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        message.getVideoId());
            } else {
                logger.error("视频分析任务发送失败: videoId={}, error={}",
                        message.getVideoId(), ex.getMessage(), ex);
            }
        });
    }

    /**
     * 同步发送视频分析任务（等待发送结果）
     *
     * @param message 视频分析消息
     * @return 发送结果
     */
    public SendResult<String, Object> sendVideoAnalysisTaskSync(VideoAnalysisMessage message) {
        String topic = KafkaTopicConstants.VIDEO_ANALYSIS_TOPIC;
        String key = message.getVideoId();

        logger.info("同步发送视频分析任务到Kafka: topic={}, videoId={}, taskId={}",
                topic, message.getVideoId(), message.getTaskId());

        try {
            SendResult<String, Object> result = kafkaTemplate.send(topic, key, message).get();
            logger.info("视频分析任务发送成功: topic={}, partition={}, offset={}, videoId={}",
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    message.getVideoId());
            return result;
        } catch (Exception e) {
            logger.error("视频分析任务发送失败: videoId={}, error={}",
                    message.getVideoId(), e.getMessage(), e);
            throw new RuntimeException("发送Kafka消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送带优先级的视频分析任务
     * 高优先级任务发送到特定分区
     *
     * @param message 视频分析消息
     * @param partition 指定分区（null则由Kafka自动分配）
     */
    public void sendVideoAnalysisTaskWithPriority(VideoAnalysisMessage message, Integer partition) {
        String topic = KafkaTopicConstants.VIDEO_ANALYSIS_TOPIC;
        String key = message.getVideoId();

        logger.info("发送优先级视频分析任务: topic={}, partition={}, priority={}, videoId={}",
                topic, partition, message.getPriority(), message.getVideoId());

        CompletableFuture<SendResult<String, Object>> future;
        if (partition != null) {
            future = kafkaTemplate.send(topic, partition, key, message);
        } else {
            future = kafkaTemplate.send(topic, key, message);
        }

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                logger.info("优先级任务发送成功: videoId={}, partition={}",
                        message.getVideoId(), result.getRecordMetadata().partition());
            } else {
                logger.error("优先级任务发送失败: videoId={}, error={}",
                        message.getVideoId(), ex.getMessage(), ex);
            }
        });
    }
}

