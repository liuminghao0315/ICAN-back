package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.AnalysisTaskVO;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.MockAlgorithmService;
import com.ican.project.service.MinioService;
import com.ican.project.config.RabbitMQConfig;
import com.ican.project.websocket.TaskProgressWebSocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mock算法服务实现
 * 模拟真实的视频分析算法，生成模拟分析结果
 */
@Service
@EnableAsync
public class MockAlgorithmServiceImpl implements MockAlgorithmService {
    
    private static final Logger logger = LoggerFactory.getLogger(MockAlgorithmServiceImpl.class);
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    @Autowired
    @Lazy
    private AnalysisTaskService analysisTaskService;
    
    @Autowired
    private AnalysisResultService analysisResultService;
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Autowired
    private MinioService minioService;
    
    // 随机数生成器
    private final Random random = new Random();
    
    // 模拟的高校名称列表
    private static final List<String> UNIVERSITY_NAMES = Arrays.asList(
            "清华大学", "北京大学", "复旦大学", "上海交通大学", "浙江大学",
            "南京大学", "中国科学技术大学", "哈尔滨工业大学", "西安交通大学", "武汉大学",
            "华中科技大学", "中山大学", "四川大学", "同济大学", "北京航空航天大学"
    );
    
    // 模拟的主题分类
    private static final List<String> TOPIC_CATEGORIES = Arrays.asList(
            "校园生活", "学术讨论", "社团活动", "体育运动", "艺术表演",
            "科技创新", "创业分享", "心理健康", "就业指导", "社会实践"
    );
    
    // 模拟的关键词库
    private static final List<String> KEYWORDS_POOL = Arrays.asList(
            "大学生", "校园", "学习", "考试", "社团", "青春", "梦想", "奋斗",
            "创新", "创业", "实习", "就业", "考研", "保研", "留学", "志愿者",
            "比赛", "获奖", "论文", "实验", "项目", "团队", "合作", "成长"
    );
    
    // 模拟的场景类型
    private static final List<String> SCENE_TYPES = Arrays.asList(
            "教室", "图书馆", "操场", "宿舍", "食堂", "实验室", "报告厅", "校园户外"
    );
    
    @PostConstruct
    public void init() {
        logger.info("Mock算法服务初始化完成");
        // 注意：不在这里启动处理器，因为@Async在@PostConstruct阶段不生效
        // 改为在应用完全启动后通过事件监听器启动
    }
    
    /**
     * 应用完全启动后再启动处理器
     * 这样可以确保@Async代理已经完全初始化
     */
    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("应用启动完成，启动Mock算法处理器");
        startProcessor();
    }
    
    @PreDestroy
    public void destroy() {
        stopProcessor();
        logger.info("Mock算法服务已停止");
    }
    
    @Override
    @Async
    public void startProcessor() {
        if (running.compareAndSet(false, true)) {
            shouldStop.set(false);
            logger.info("Mock算法处理器启动");
            
            while (!shouldStop.get()) {
                try {
                    // 获取待处理任务
                    AnalysisTaskVO task = analysisTaskService.getPendingTask();
                    
                    if (task != null) {
                        processTask(task);
                    } else {
                        // 没有任务时休眠一段时间
                        Thread.sleep(5000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("Mock算法处理器被中断");
                    break;
                } catch (Exception e) {
                    logger.error("Mock算法处理器异常: {}", e.getMessage(), e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            running.set(false);
            logger.info("Mock算法处理器已停止");
        }
    }
    
    @Override
    public void stopProcessor() {
        shouldStop.set(true);
        logger.info("正在停止Mock算法处理器...");
    }
    
    @Override
    public boolean processOneTask() {
        AnalysisTaskVO task = analysisTaskService.getPendingTask();
        if (task != null) {
            processTask(task);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * 处理单个任务
     * 将任务发送到 RabbitMQ 队列，由 Python 脚本处理
     */
    private void processTask(AnalysisTaskVO task) {
        String taskId = task.getId();
        String videoId = task.getVideoId();
        
        // 获取任务对应的用户ID，用于WebSocket通知
        AnalysisTask taskEntity = analysisTaskMapper.selectById(taskId);
        String userId = taskEntity != null ? taskEntity.getUserId() : null;
        
        logger.info("开始处理分析任务: taskId={}, videoId={}, userId={}", taskId, videoId, userId);
        
        try {
            // 1. 标记任务开始处理
            analysisTaskService.markTaskProcessing(taskId);
            
            // 发送WebSocket通知：任务开始处理
            if (userId != null) {
                TaskProgressWebSocket.sendTaskProgress(userId, taskId, videoId,
                        AnalysisTask.Status.PROCESSING.name(), 0, "任务开始处理");
            }
            
            // 获取视频信息
            Video video = videoMapper.selectById(videoId);
            if (video == null) {
                throw new RuntimeException("视频不存在: " + videoId);
            }
            
            // 2. 构造任务消息，发送到 RabbitMQ 队列，由 Python 脚本处理
            Map<String, Object> taskMessage = new HashMap<>();
            taskMessage.put("taskId", taskId);
            taskMessage.put("videoId", videoId);
            // 使用 MinioService 获取视频 URL
            String videoUrl = video.getFilePath() != null ? minioService.getFileUrl(video.getFilePath()) : null;
            taskMessage.put("videoUrl", videoUrl);
            taskMessage.put("videoTitle", video.getTitle());
            taskMessage.put("videoDuration", video.getDuration());
            taskMessage.put("fileSize", video.getFileSize());
            
            // 发送任务到 RabbitMQ 队列
            rabbitTemplate.convertAndSend(RabbitMQConfig.ALGORITHM_TASK_QUEUE, JSON.toJSONString(taskMessage));
            
            logger.info("任务已发送到 RabbitMQ 队列，等待 Python 脚本处理: taskId={}", taskId);
            
            // 注意：进度更新和结果保存将由 Python 脚本通过 RabbitMQ 结果队列返回
            // Java 端的 AlgorithmResultListener 会监听结果队列并处理
            
        } catch (Exception e) {
            logger.error("发送任务到队列失败: taskId={}, error={}", taskId, e.getMessage(), e);
            analysisTaskService.markTaskFailed(taskId, e.getMessage());
            
            // 发送WebSocket通知：任务失败
            if (userId != null) {
                TaskProgressWebSocket.sendTaskFailed(userId, taskId, videoId, e.getMessage());
            }
        }
    }
    
    // ========== 以下方法已删除（使用RabbitMQ架构，不再需要生成Mock结果） ==========
    // generateMockResult()
    // generateRiskScore()
    // calculateRiskLevel()
    // calculateSentimentLabel()
    
    // ========== 以下方法已删除（使用RabbitMQ架构，不再需要生成Mock数据） ==========
    // generateKeywords()
    // generateVideoFeatures()
    // generateAudioFeatures()
    // generateTextFeatures()
    // generateTranscription()
    // generateAudienceAnalysis()
}

