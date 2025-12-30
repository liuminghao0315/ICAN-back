package com.ican.project.kafka.config;

/**
 * Kafka Topic 常量定义
 * 集中管理所有 Topic 名称，避免硬编码
 */
public class KafkaTopicConstants {

    /**
     * 视频分析任务 Topic
     * 用于发送视频分析请求到消息队列
     */
    public static final String VIDEO_ANALYSIS_TOPIC = "video-analysis-topic";

    /**
     * 分析结果 Topic
     * 用于接收算法服务处理完成后的结果
     */
    public static final String ANALYSIS_RESULT_TOPIC = "analysis-result-topic";

    /**
     * 消费者组 ID
     */
    public static final String VIDEO_ANALYSIS_GROUP = "ican-video-analysis-group";

    private KafkaTopicConstants() {
        // 私有构造函数，防止实例化
    }
}

