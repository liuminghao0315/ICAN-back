package com.ican.project.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 */
@Configuration
public class RabbitMQConfig {
    
    /**
     * 算法分析任务队列名称（Java -> Python）
     */
    public static final String ALGORITHM_TASK_QUEUE = "algorithm.task.queue";
    
    /**
     * 算法分析结果队列名称（Python -> Java）
     */
    public static final String ALGORITHM_RESULT_QUEUE = "algorithm.result.queue";
    
    /**
     * 定义算法分析任务队列（Java 发送任务给 Python）
     */
    @Bean
    public Queue algorithmTaskQueue() {
        return new Queue(ALGORITHM_TASK_QUEUE, true);
    }
    
    /**
     * 定义算法分析结果队列（Python 发送结果给 Java）
     */
    @Bean
    public Queue algorithmResultQueue() {
        return new Queue(ALGORITHM_RESULT_QUEUE, true);
    }
    
    /**
     * 配置 RabbitMQ 监听器容器工厂
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        // 使用默认的错误处理器，不需要手动配置
        return factory;
    }
}

