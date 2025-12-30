package com.ican.project.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Kafka 配置类
 * 配置 Kafka Topic 和消费者工厂
 * 当 kafka.enabled=false 时禁用 Kafka
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaConfig {

    @Value("${kafka.topic.video-analysis}")
    private String videoAnalysisTopic;

    @Value("${kafka.topic.analysis-result}")
    private String analysisResultTopic;

    /**
     * 创建视频分析任务 Topic
     * 用于接收视频上传后的分析请求
     */
    @Bean
    public NewTopic videoAnalysisTopic() {
        return TopicBuilder.name(videoAnalysisTopic)
                .partitions(3)  // 3个分区，支持并行消费
                .replicas(1)    // 单节点开发环境，1个副本
                .build();
    }

    /**
     * 创建分析结果 Topic
     * 用于算法服务处理完成后发送结果
     */
    @Bean
    public NewTopic analysisResultTopic() {
        return TopicBuilder.name(analysisResultTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 配置 Kafka 消费者容器工厂
     * 使用手动确认模式，确保消息处理完成后才提交偏移量
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // 设置手动确认模式
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 设置并发消费者数量
        factory.setConcurrency(3);
        return factory;
    }
}

