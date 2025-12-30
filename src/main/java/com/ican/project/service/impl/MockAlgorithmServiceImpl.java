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
import com.ican.project.websocket.TaskProgressWebSocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    
    /**
     * Kafka 是否启用
     * 当 Kafka 启用时，不启动自动轮询处理器，由 Kafka 消费者处理任务
     */
    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;
    
    @Autowired
    @Lazy
    private AnalysisTaskService analysisTaskService;
    
    @Autowired
    private AnalysisResultService analysisResultService;
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    
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
     * 
     * 注意：当 Kafka 启用时，不启动自动轮询处理器
     * 任务由 Kafka 消费者 (VideoAnalysisConsumer) 处理
     */
    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (kafkaEnabled) {
            logger.info("Kafka已启用，Mock算法处理器不启动自动轮询（由Kafka消费者处理任务）");
            return;
        }
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
    
    @Override
    @Async
    public void analyzeVideoAsync(String taskId, String videoId, String filePath) {
        logger.info("Kafka触发的异步视频分析: taskId={}, videoId={}, filePath={}", taskId, videoId, filePath);
        
        AnalysisTask taskEntity = analysisTaskMapper.selectById(taskId);
        if (taskEntity == null) {
            logger.error("任务不存在: taskId={}", taskId);
            return;
        }
        
        String userId = taskEntity.getUserId();
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            logger.error("视频不存在: videoId={}", videoId);
            analysisTaskService.markTaskFailed(taskId, "视频不存在");
            return;
        }
        
        try {
            // 模拟分析过程
            simulateAnalysisProcess(taskId, videoId, video, userId);
            
            // 生成模拟结果
            AnalysisResult result = generateMockResult(taskId, videoId, video);
            
            // 保存结果
            String resultId = analysisResultService.saveResult(result);
            
            // 标记任务完成
            analysisTaskService.markTaskCompleted(taskId);
            
            // 发送WebSocket通知
            if (userId != null) {
                TaskProgressWebSocket.sendTaskCompleted(userId, taskId, videoId, resultId);
            }
            
            logger.info("Kafka异步分析完成: taskId={}, resultId={}", taskId, resultId);
            
        } catch (Exception e) {
            logger.error("Kafka异步分析失败: taskId={}, error={}", taskId, e.getMessage(), e);
            analysisTaskService.markTaskFailed(taskId, e.getMessage());
            
            if (userId != null) {
                TaskProgressWebSocket.sendTaskFailed(userId, taskId, videoId, e.getMessage());
            }
        }
    }
    
    /**
     * 处理单个任务
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
            
            // 2. 模拟分析过程（分阶段更新进度）
            simulateAnalysisProcess(taskId, videoId, video, userId);
            
            // 3. 生成模拟分析结果
            AnalysisResult result = generateMockResult(taskId, videoId, video);
            
            // 4. 保存分析结果
            String resultId = analysisResultService.saveResult(result);
            
            // 5. 标记任务完成
            analysisTaskService.markTaskCompleted(taskId);
            
            // 发送WebSocket通知：任务完成
            if (userId != null) {
                TaskProgressWebSocket.sendTaskCompleted(userId, taskId, videoId, resultId);
            }
            
            logger.info("分析任务完成: taskId={}, riskLevel={}", taskId, result.getRiskLevel());
            
        } catch (Exception e) {
            logger.error("分析任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            analysisTaskService.markTaskFailed(taskId, e.getMessage());
            
            // 发送WebSocket通知：任务失败
            if (userId != null) {
                TaskProgressWebSocket.sendTaskFailed(userId, taskId, videoId, e.getMessage());
            }
        }
    }
    
    /**
     * 模拟分析过程，分阶段更新进度
     */
    private void simulateAnalysisProcess(String taskId, String videoId, Video video, String userId) throws InterruptedException {
        // 根据视频大小计算模拟处理时间（5-30秒）
        long fileSize = video.getFileSize() != null ? video.getFileSize() : 1024 * 1024;
        int baseTime = 5000; // 5秒基础时间
        int extraTime = (int) Math.min(25000, fileSize / (1024 * 1024) * 500); // 每MB增加0.5秒
        int totalTime = baseTime + random.nextInt(extraTime + 1);
        
        // 分5个阶段更新进度
        String[] stages = {"视频预处理", "视频特征提取", "音频分析", "文本分析", "综合评估"};
        int stageTime = totalTime / stages.length;
        
        for (int i = 0; i < stages.length; i++) {
            int progress = (i + 1) * 20;
            String stageName = stages[i];
            
            logger.debug("任务 {} 进度: {}% - {}", taskId, progress, stageName);
            analysisTaskService.updateTaskStatus(taskId, 
                    AnalysisTask.Status.PROCESSING.name(), progress, null);
            
            // 发送WebSocket进度通知
            if (userId != null) {
                TaskProgressWebSocket.sendTaskProgress(userId, taskId, videoId,
                        AnalysisTask.Status.PROCESSING.name(), progress, stageName);
            }
            
            // 模拟处理时间
            Thread.sleep(stageTime);
        }
    }
    
    /**
     * 生成模拟分析结果
     */
    private AnalysisResult generateMockResult(String taskId, String videoId, Video video) {
        // 生成风险评分 (0-1)
        // 大多数视频是低风险的，少数是高风险
        double riskScore = generateRiskScore();
        String riskLevel = calculateRiskLevel(riskScore);
        
        // 生成高校相关信息
        boolean isUniversityRelated = random.nextDouble() > 0.3; // 70%概率是高校相关
        String universityName = null;
        BigDecimal universityConfidence = null;
        if (isUniversityRelated) {
            universityName = UNIVERSITY_NAMES.get(random.nextInt(UNIVERSITY_NAMES.size()));
            universityConfidence = BigDecimal.valueOf(0.7 + random.nextDouble() * 0.3)
                    .setScale(4, RoundingMode.HALF_UP);
        }
        
        // 生成主题分类和关键词
        String topicCategory = TOPIC_CATEGORIES.get(random.nextInt(TOPIC_CATEGORIES.size()));
        List<String> keywords = generateKeywords(3 + random.nextInt(5));
        
        // 生成情感分析结果
        double sentimentScore = -1 + random.nextDouble() * 2; // -1 到 1
        String sentimentLabel = calculateSentimentLabel(sentimentScore);
        
        // 生成视频特征
        Map<String, Object> videoFeatures = generateVideoFeatures(video);
        
        // 生成音频特征
        Map<String, Object> audioFeatures = generateAudioFeatures();
        
        // 生成文本特征
        Map<String, Object> textFeatures = generateTextFeatures(video);
        
        // 生成模拟转写文本
        String transcription = generateTranscription();
        
        // 生成受众分析
        Map<String, Object> audienceAnalysis = generateAudienceAnalysis();
        
        // 生成传播潜力评分
        BigDecimal spreadPotential = BigDecimal.valueOf(random.nextDouble())
                .setScale(4, RoundingMode.HALF_UP);
        
        return AnalysisResult.builder()
                .id(IdUtil.fastSimpleUUID())
                .taskId(taskId)
                .videoId(videoId)
                .riskScore(BigDecimal.valueOf(riskScore).setScale(4, RoundingMode.HALF_UP))
                .riskLevel(riskLevel)
                .isUniversityRelated(isUniversityRelated)
                .universityName(universityName)
                .universityConfidence(universityConfidence)
                .topicCategory(topicCategory)
                .topicKeywords(JSON.toJSONString(keywords))
                .sentimentScore(BigDecimal.valueOf(sentimentScore).setScale(4, RoundingMode.HALF_UP))
                .sentimentLabel(sentimentLabel)
                .videoFeatures(JSON.toJSONString(videoFeatures))
                .audioFeatures(JSON.toJSONString(audioFeatures))
                .transcription(transcription)
                .textFeatures(JSON.toJSONString(textFeatures))
                .audienceAnalysis(JSON.toJSONString(audienceAnalysis))
                .spreadPotential(spreadPotential)
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
    }
    
    /**
     * 生成风险评分
     * 使用正态分布，大多数视频风险较低
     */
    private double generateRiskScore() {
        // 使用正态分布，均值0.3，标准差0.2
        double score = random.nextGaussian() * 0.2 + 0.3;
        // 限制在0-1范围内
        return Math.max(0, Math.min(1, score));
    }
    
    /**
     * 计算风险等级
     */
    private String calculateRiskLevel(double riskScore) {
        if (riskScore < 0.3) {
            return AnalysisResult.RiskLevel.LOW.name();
        } else if (riskScore < 0.7) {
            return AnalysisResult.RiskLevel.MEDIUM.name();
        } else {
            return AnalysisResult.RiskLevel.HIGH.name();
        }
    }
    
    /**
     * 计算情感标签
     */
    private String calculateSentimentLabel(double sentimentScore) {
        if (sentimentScore < -0.3) {
            return AnalysisResult.SentimentLabel.NEGATIVE.name();
        } else if (sentimentScore > 0.3) {
            return AnalysisResult.SentimentLabel.POSITIVE.name();
        } else {
            return AnalysisResult.SentimentLabel.NEUTRAL.name();
        }
    }
    
    /**
     * 生成随机关键词
     */
    private List<String> generateKeywords(int count) {
        List<String> keywords = new ArrayList<>();
        List<String> pool = new ArrayList<>(KEYWORDS_POOL);
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            keywords.add(pool.get(i));
        }
        return keywords;
    }
    
    /**
     * 生成视频特征
     */
    private Map<String, Object> generateVideoFeatures(Video video) {
        Map<String, Object> features = new HashMap<>();
        
        // 基础信息
        features.put("duration", video.getDuration() != null ? video.getDuration() : 60.0 + random.nextDouble() * 300);
        features.put("width", video.getWidth() != null ? video.getWidth() : 1920);
        features.put("height", video.getHeight() != null ? video.getHeight() : 1080);
        features.put("fps", 24 + random.nextInt(36));
        
        // 场景识别
        features.put("sceneType", SCENE_TYPES.get(random.nextInt(SCENE_TYPES.size())));
        features.put("sceneConfidence", 0.7 + random.nextDouble() * 0.3);
        
        // 人脸检测
        features.put("faceCount", random.nextInt(10));
        features.put("hasPerson", random.nextBoolean());
        
        // 画质评估
        features.put("qualityScore", 0.5 + random.nextDouble() * 0.5);
        features.put("brightness", 0.3 + random.nextDouble() * 0.4);
        features.put("clarity", 0.6 + random.nextDouble() * 0.4);
        
        return features;
    }
    
    /**
     * 生成音频特征
     */
    private Map<String, Object> generateAudioFeatures() {
        Map<String, Object> features = new HashMap<>();
        
        features.put("hasAudio", true);
        features.put("audioQuality", 0.6 + random.nextDouble() * 0.4);
        features.put("speechRatio", 0.3 + random.nextDouble() * 0.5);
        features.put("musicRatio", random.nextDouble() * 0.3);
        features.put("noiseLevel", random.nextDouble() * 0.3);
        features.put("volumeLevel", 0.4 + random.nextDouble() * 0.4);
        features.put("emotionInVoice", random.nextDouble() > 0.5 ? "calm" : "energetic");
        
        return features;
    }
    
    /**
     * 生成文本特征
     */
    private Map<String, Object> generateTextFeatures(Video video) {
        Map<String, Object> features = new HashMap<>();
        
        features.put("titleLength", video.getTitle() != null ? video.getTitle().length() : 0);
        features.put("hasDescription", video.getDescription() != null && !video.getDescription().isEmpty());
        features.put("descriptionLength", video.getDescription() != null ? video.getDescription().length() : 0);
        features.put("titleSentiment", random.nextDouble() * 2 - 1);
        features.put("containsKeywords", random.nextBoolean());
        features.put("languageConfidence", 0.9 + random.nextDouble() * 0.1);
        
        return features;
    }
    
    /**
     * 生成模拟转写文本
     */
    private String generateTranscription() {
        String[] transcriptions = {
                "大家好，今天我来分享一下在大学的学习经验...",
                "欢迎来到我们的校园，这里是图书馆...",
                "同学们，今天我们来讨论一个有趣的话题...",
                "这是我们社团的活动记录，非常精彩...",
                "在实验室里，我们进行了一系列的实验...",
                "感谢大家的支持，希望我的分享对你们有帮助..."
        };
        return transcriptions[random.nextInt(transcriptions.length)];
    }
    
    /**
     * 生成受众分析
     */
    private Map<String, Object> generateAudienceAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        
        // 目标受众年龄分布
        Map<String, Double> ageDistribution = new HashMap<>();
        ageDistribution.put("18-24", 0.4 + random.nextDouble() * 0.3);
        ageDistribution.put("25-34", 0.2 + random.nextDouble() * 0.2);
        ageDistribution.put("35-44", random.nextDouble() * 0.15);
        ageDistribution.put("45+", random.nextDouble() * 0.1);
        analysis.put("ageDistribution", ageDistribution);
        
        // 预测兴趣标签
        List<String> interests = Arrays.asList("教育", "校园生活", "青年文化");
        analysis.put("predictedInterests", interests);
        
        // 预测观看量
        analysis.put("predictedViews", 100 + random.nextInt(10000));
        
        // 互动率预测
        analysis.put("predictedEngagement", random.nextDouble() * 0.1);
        
        return analysis;
    }
}

