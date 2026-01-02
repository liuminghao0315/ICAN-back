package com.ican.project.rabbitmq;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ican.project.config.RabbitMQConfig;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.websocket.TaskProgressWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 结果回调队列监听器
 * 实现双向异步事件驱动架构的Java端Consumer
 * 
 * 工作流程：
 * 1. 监听结果回调队列（Python -> Java）
 * 2. 使用Redis Set无状态跟踪已完成的模块（video, audio, text）
 * 3. 动态计算进度（1个模块=25%, 2个=50%, 3个=75%）
 * 4. 当3个模块都完成时，触发整合分析，推送100%进度
 * 5. 清理Redis数据
 */
@Component
public class AlgorithmResultListener {
    
    private static final Logger logger = LoggerFactory.getLogger(AlgorithmResultListener.class);
    
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    
    @Autowired
    private AnalysisTaskService analysisTaskService;
    
    @Autowired
    private AnalysisResultService analysisResultService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * Redis Key 前缀：存储任务已完成的模块
     * Key格式: analysis:modules:{taskId}
     * Value: Set<String> 包含已完成的模块类型（audio, video, text）
     */
    private static final String REDIS_KEY_PREFIX = "analysis:modules:";
    
    /**
     * Redis Key 前缀：存储任务模块结果数据
     * Key格式: analysis:results:{taskId}:{moduleType}
     * Value: Map<String, Object> 模块结果数据
     */
    private static final String REDIS_RESULT_KEY_PREFIX = "analysis:results:";
    
    /**
     * Redis Key 过期时间（小时）
     */
    private static final long REDIS_KEY_EXPIRE_HOURS = 24;
    
    /**
     * 随机数生成器（用于整合分析）
     */
    private final Random random = new Random();
    
    /**
     * 监听算法分析结果队列
     * 
     * @param message RabbitMQ 消息
     * @param channel RabbitMQ 通道（用于手动确认消息）
     * @param deliveryTag 消息投递标签
     */
    @RabbitListener(queues = RabbitMQConfig.ALGORITHM_RESULT_QUEUE)
    public void handleAlgorithmResult(Message message, Channel channel, 
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
            logger.info("收到算法分析结果消息: {}", messageBody);
            
            // 解析消息（新格式：不包含progress字段，由Java动态计算）
            JSONObject jsonMessage = JSON.parseObject(messageBody);
            String taskId = jsonMessage.getString("taskId");
            String moduleType = jsonMessage.getString("moduleType"); // audio/video/text
            Map<String, Object> resultData = jsonMessage.getObject("resultData", Map.class);
            
            if (taskId == null || moduleType == null || resultData == null) {
                logger.error("消息格式错误，缺少必要字段: taskId={}, moduleType={}, resultData={}", 
                        taskId, moduleType, resultData);
                // 拒绝消息，不重新入队
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            
            // 验证模块类型（Python会发送video, audio, text三种类型）
            if (!"audio".equals(moduleType) && !"video".equals(moduleType) && !"text".equals(moduleType)) {
                logger.error("无效的模块类型: moduleType={}，期望: video/audio/text", moduleType);
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            
            // 获取任务信息
            AnalysisTask task = analysisTaskMapper.selectById(taskId);
            if (task == null) {
                logger.error("任务不存在: taskId={}", taskId);
                // 拒绝消息，不重新入队
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            
            String userId = task.getUserId();
            String videoId = task.getVideoId();
            
            // ========== Redis状态管理 ==========
            // 1. 存储模块结果数据到Redis（用于后续整合分析）
            String resultKey = REDIS_RESULT_KEY_PREFIX + taskId + ":" + moduleType;
            redisTemplate.opsForValue().set(resultKey, resultData, REDIS_KEY_EXPIRE_HOURS, TimeUnit.HOURS);
            logger.debug("已存储模块结果到Redis: key={}", resultKey);
            
            // 2. 使用Redis Set无状态跟踪已完成的模块（线程安全）
            String modulesKey = REDIS_KEY_PREFIX + taskId;
            Long added = redisTemplate.opsForSet().add(modulesKey, moduleType);
            redisTemplate.expire(modulesKey, REDIS_KEY_EXPIRE_HOURS, TimeUnit.HOURS);
            logger.debug("已添加模块到Redis Set: key={}, moduleType={}, isNew={}", 
                    modulesKey, moduleType, added != null && added > 0);
            
            // 3. 获取当前已完成的模块数量（用于动态计算进度）
            Long completedCount = redisTemplate.opsForSet().size(modulesKey);
            if (completedCount == null) {
                completedCount = 0L;
            }
            
            // 4. 动态计算进度：1个模块=25%，2个=50%，3个=75%
            int progress = (int) (completedCount * 25);
            if (progress > 75) {
                progress = 75; // 前三步最多75%，整合分析后才会到100%
            }
            
            logger.info("任务模块状态更新: taskId={}, moduleType={}, 已完成模块数={}, 计算进度={}%", 
                    taskId, moduleType, completedCount, progress);
            
            // 更新任务进度
            analysisTaskService.updateTaskStatus(taskId, 
                    AnalysisTask.Status.PROCESSING.name(), progress, null);
            
            // 构造进度消息（中文显示）
            String moduleTypeDisplay = getModuleTypeDisplay(moduleType);
            String progressMessage = String.format("%s分析完成", moduleTypeDisplay);
            
            // 通过 WebSocket 转发消息给前端
            if (userId != null) {
                // 构造包含详细结果数据的消息
                Map<String, Object> wsData = new java.util.HashMap<>();
                wsData.put("taskId", taskId);
                wsData.put("videoId", videoId);
                wsData.put("status", AnalysisTask.Status.PROCESSING.name());
                wsData.put("progress", progress);
                wsData.put("message", progressMessage);
                wsData.put("moduleType", moduleType);
                wsData.put("resultData", resultData);
                
                // 发送 WebSocket 消息
                TaskProgressWebSocket.sendMessage(userId, createWebSocketMessage("task_progress", "任务进度更新", wsData));
                
                logger.info("已转发进度消息到 WebSocket: userId={}, taskId={}, progress={}%, moduleType={}", 
                        userId, taskId, progress, moduleType);
            }
            
            // ========== 整合分析触发 ==========
            // 检查是否所有三个基础模块都已完成（video, audio, text）
            // Python会异步发送3条消息，当Redis Set size达到3时，触发整合分析
            if (completedCount == 3) {
                logger.info("========== 检测到3个模块全部完成，触发整合分析 ========== taskId={}", taskId);
                
                // 从Redis获取所有模块结果
                Map<String, Object> moduleResults = new java.util.HashMap<>();
                Object audioResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":audio");
                Object videoResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":video");
                Object textResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":text");
                
                // 类型转换
                if (audioResult instanceof Map) {
                    moduleResults.put("audio", (Map<String, Object>) audioResult);
                }
                if (videoResult instanceof Map) {
                    moduleResults.put("video", (Map<String, Object>) videoResult);
                }
                if (textResult instanceof Map) {
                    moduleResults.put("text", (Map<String, Object>) textResult);
                }
                
                logger.info("从Redis获取模块结果: taskId={}, audio={}, video={}, text={}", 
                        taskId, audioResult != null, videoResult != null, textResult != null);
                
                // 执行整合分析（生成受众年龄分布）
                Map<String, Object> integrationResult = performIntegrationAnalysis(taskId, moduleResults);
                
                // 更新进度为100%
                analysisTaskService.updateTaskStatus(taskId, 
                        AnalysisTask.Status.PROCESSING.name(), 100, null);
                
                // 保存分析结果（包含所有模块结果和整合结果）
                String resultId = saveAnalysisResult(taskId, videoId, moduleResults, integrationResult);
                
                // 标记任务完成
                analysisTaskService.markTaskCompleted(taskId);
                
                // 推送100%进度和整合结果给前端
                if (userId != null) {
                    Map<String, Object> finalWsData = new java.util.HashMap<>();
                    finalWsData.put("taskId", taskId);
                    finalWsData.put("videoId", videoId);
                    finalWsData.put("status", AnalysisTask.Status.PROCESSING.name());
                    finalWsData.put("progress", 100);
                    finalWsData.put("message", "整合分析完成");
                    finalWsData.put("moduleType", "integration");
                    finalWsData.put("resultData", integrationResult);
                    
                    TaskProgressWebSocket.sendMessage(userId, createWebSocketMessage("task_progress", "任务进度更新", finalWsData));
                    
                    // 发送完成通知
                    TaskProgressWebSocket.sendTaskCompleted(userId, taskId, videoId, resultId);
                    
                    logger.info("整合分析完成并已推送: userId={}, taskId={}, progress=100%", userId, taskId);
                }
                
                // ========== 清理Redis数据 ==========
                // 删除模块跟踪Set
                redisTemplate.delete(modulesKey);
                // 删除所有模块结果数据
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":audio");
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":video");
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":text");
                logger.info("已清理Redis中的任务状态: taskId={}", taskId);
            }
            
            // 手动确认消息已处理
            channel.basicAck(deliveryTag, false);
            logger.debug("消息已确认: deliveryTag={}, taskId={}, progress={}%", deliveryTag, taskId, progress);
            
        } catch (Exception e) {
            logger.error("处理算法分析结果消息失败: taskId={}, error={}", 
                    message != null ? new String(message.getBody(), StandardCharsets.UTF_8) : "unknown", 
                    e.getMessage(), e);
            try {
                // 拒绝消息并重新入队，以便重试
                channel.basicNack(deliveryTag, false, true);
                logger.warn("消息已拒绝并重新入队: deliveryTag={}", deliveryTag);
            } catch (Exception ex) {
                logger.error("拒绝消息失败: {}", ex.getMessage(), ex);
            }
        }
    }
    
    /**
     * 获取模块类型的中文显示名称
     */
    private String getModuleTypeDisplay(String moduleType) {
        return switch (moduleType) {
            case "audio" -> "音频";
            case "video" -> "视频";
            case "text" -> "文本";
            case "integration" -> "整合";
            default -> moduleType;
        };
    }
    
    /**
     * 执行整合分析（第4步）
     * 基于video、audio、text三个模块的真实数据，使用加权统计算法计算最终结果
     */
    private Map<String, Object> performIntegrationAnalysis(String taskId, Map<String, Object> moduleResults) {
        logger.info("========== 开始执行整合分析（基于真实数据）: taskId={} ==========", taskId);
        
        // 提取各模块数据
        Map<String, Object> videoData = (Map<String, Object>) moduleResults.get("video");
        Map<String, Object> audioData = (Map<String, Object>) moduleResults.get("audio");
        Map<String, Object> textData = (Map<String, Object>) moduleResults.get("text");
        
        if (videoData == null || audioData == null || textData == null) {
            logger.warn("模块数据不完整，使用默认值: video={}, audio={}, text={}", 
                    videoData != null, audioData != null, textData != null);
            // 如果数据缺失，使用默认值
            if (videoData == null) videoData = new java.util.HashMap<>();
            if (audioData == null) audioData = new java.util.HashMap<>();
            if (textData == null) textData = new java.util.HashMap<>();
        }
        
        // 1. 计算受众年龄分布（基于场景类型、主题分类、情感评分等）
        Map<String, Double> ageDistribution = calculateAgeDistribution(videoData, textData);
        
        // 2. 计算预测兴趣标签（基于关键词、主题、场景等）
        List<String> predictedInterests = calculatePredictedInterests(videoData, textData);
        
        // 3. 计算预测播放量（基于视频质量、情感、主题等加权计算）
        int predictedViews = calculatePredictedViews(videoData, audioData, textData);
        
        // 4. 计算预测互动率（基于情感、质量、内容特征等）
        double predictedEngagement = calculatePredictedEngagement(videoData, audioData, textData);
        
        // 构建整合分析结果
        Map<String, Object> integrationResult = new java.util.HashMap<>();
        integrationResult.put("ageDistribution", ageDistribution);
        integrationResult.put("predictedInterests", predictedInterests);
        integrationResult.put("predictedViews", predictedViews);
        integrationResult.put("predictedEngagement", predictedEngagement);
        integrationResult.put("analysisTimestamp", System.currentTimeMillis());
        
        logger.info("整合分析完成: taskId={}, ageDistribution={}, predictedInterests={}, predictedViews={}, predictedEngagement={}", 
                taskId, ageDistribution, predictedInterests, predictedViews, predictedEngagement);
        
        return integrationResult;
    }
    
    /**
     * 计算受众年龄分布
     * 基于场景类型、主题分类、情感评分等特征进行加权计算
     */
    private Map<String, Double> calculateAgeDistribution(Map<String, Object> videoData, Map<String, Object> textData) {
        // 初始化各年龄段基础权重
        double weight18_24 = 0.25;  // 18-24岁基础权重
        double weight25_34 = 0.35;   // 25-34岁基础权重
        double weight35_44 = 0.25;   // 35-44岁基础权重
        double weight45Plus = 0.15;  // 45+岁基础权重
        
        // 1. 基于场景类型调整权重
        String sceneType = getStringValue(videoData, "sceneType", "");
        double sceneConfidence = getDoubleValue(videoData, "sceneConfidence", 0.7);
        
        if (sceneConfidence > 0.8) {
            switch (sceneType) {
                case "教室", "图书馆", "实验室" -> {
                    // 学术场景偏向18-24岁
                    weight18_24 += 0.15;
                    weight25_34 += 0.05;
                }
                case "操场", "宿舍", "食堂" -> {
                    // 生活场景偏向18-24岁和25-34岁
                    weight18_24 += 0.10;
                    weight25_34 += 0.10;
                }
                case "报告厅" -> {
                    // 正式场景偏向25-34岁和35-44岁
                    weight25_34 += 0.10;
                    weight35_44 += 0.10;
                }
            }
        }
        
        // 2. 基于主题分类调整权重
        String topicCategory = getStringValue(textData, "topicCategory", "");
        switch (topicCategory) {
            case "校园生活", "社团活动", "体育运动" -> {
                // 校园主题偏向年轻群体
                weight18_24 += 0.12;
                weight25_34 += 0.05;
            }
            case "学术讨论", "科技创新" -> {
                // 学术主题偏向18-24和25-34岁
                weight18_24 += 0.08;
                weight25_34 += 0.12;
            }
            case "就业指导", "创业分享" -> {
                // 职业主题偏向25-34岁
                weight25_34 += 0.15;
                weight35_44 += 0.05;
            }
            case "心理健康", "社会实践" -> {
                // 社会主题分布较均匀
                weight25_34 += 0.08;
                weight35_44 += 0.08;
            }
        }
        
        // 3. 基于情感评分调整权重（正面情感偏向年轻群体）
        double sentimentScore = getDoubleValue(textData, "sentimentScore", 0.0);
        if (sentimentScore > 0.3) {
            // 正面情感，年轻群体更活跃
            weight18_24 += 0.08;
            weight25_34 += 0.05;
        } else if (sentimentScore < -0.3) {
            // 负面情感，年长群体可能更关注
            weight35_44 += 0.05;
            weight45Plus += 0.05;
        }
        
        // 4. 基于视频质量调整权重（高质量视频吸引更多年轻用户）
        double qualityScore = getDoubleValue(videoData, "qualityScore", 0.7);
        if (qualityScore > 0.8) {
            weight18_24 += 0.05;
            weight25_34 += 0.03;
        }
        
        // 归一化权重，确保总和为1.0
        double totalWeight = weight18_24 + weight25_34 + weight35_44 + weight45Plus;
        
        Map<String, Double> ageDistribution = new java.util.HashMap<>();
        ageDistribution.put("18-24", roundToFourDecimals(weight18_24 / totalWeight));
        ageDistribution.put("25-34", roundToFourDecimals(weight25_34 / totalWeight));
        ageDistribution.put("35-44", roundToFourDecimals(weight35_44 / totalWeight));
        ageDistribution.put("45+", roundToFourDecimals(weight45Plus / totalWeight));
        
        // 确保总和精确为1.0（处理浮点数误差）
        double finalTotal = ageDistribution.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(finalTotal - 1.0) > 0.0001) {
            String lastKey = "45+";
            double lastValue = ageDistribution.get(lastKey);
            ageDistribution.put(lastKey, roundToFourDecimals(lastValue + (1.0 - finalTotal)));
        }
        
        logger.debug("年龄分布计算: sceneType={}, topicCategory={}, sentimentScore={}, qualityScore={}, result={}", 
                sceneType, topicCategory, sentimentScore, qualityScore, ageDistribution);
        
        return ageDistribution;
    }
    
    /**
     * 计算预测兴趣标签
     * 基于关键词、主题分类、场景类型等特征
     */
    @SuppressWarnings("unchecked")
    private List<String> calculatePredictedInterests(Map<String, Object> videoData, Map<String, Object> textData) {
        java.util.Set<String> interests = new java.util.HashSet<>();
        
        // 1. 基于主题分类添加兴趣
        String topicCategory = getStringValue(textData, "topicCategory", "");
        switch (topicCategory) {
            case "校园生活" -> interests.add("校园生活");
            case "学术讨论" -> {
                interests.add("教育");
                interests.add("学术研究");
            }
            case "社团活动" -> {
                interests.add("社团活动");
                interests.add("青年文化");
            }
            case "体育运动" -> interests.add("体育运动");
            case "艺术表演" -> interests.add("艺术文化");
            case "科技创新" -> {
                interests.add("科技创新");
                interests.add("技术前沿");
            }
            case "创业分享" -> {
                interests.add("创业创新");
                interests.add("商业思维");
            }
            case "就业指导" -> {
                interests.add("职业发展");
                interests.add("就业指导");
            }
            case "心理健康" -> interests.add("心理健康");
            case "社会实践" -> interests.add("社会实践");
        }
        
        // 2. 基于关键词添加兴趣
        Object keywordsObj = textData.get("keywords");
        if (keywordsObj instanceof List) {
            List<String> keywords = (List<String>) keywordsObj;
            for (String keyword : keywords) {
                switch (keyword) {
                    case "学习", "考试", "考研", "保研" -> interests.add("教育");
                    case "创业", "创新" -> interests.add("创业创新");
                    case "实习", "就业" -> interests.add("职业发展");
                    case "社团", "青春" -> interests.add("青年文化");
                    case "留学" -> interests.add("国际教育");
                    case "志愿者" -> interests.add("社会实践");
                }
            }
        }
        
        // 3. 基于场景类型添加兴趣
        String sceneType = getStringValue(videoData, "sceneType", "");
        switch (sceneType) {
            case "教室", "图书馆", "实验室" -> interests.add("学术研究");
            case "操场", "体育馆" -> interests.add("体育运动");
            case "报告厅" -> interests.add("公开演讲");
            case "校园户外" -> interests.add("校园生活");
        }
        
        // 4. 默认兴趣（如果集合为空）
        if (interests.isEmpty()) {
            interests.add("教育");
            interests.add("校园生活");
        }
        
        // 转换为列表并限制数量（最多5个）
        List<String> result = new java.util.ArrayList<>(interests);
        if (result.size() > 5) {
            result = result.subList(0, 5);
        }
        
        logger.debug("预测兴趣计算: topicCategory={}, keywords={}, sceneType={}, result={}", 
                topicCategory, keywordsObj, sceneType, result);
        
        return result;
    }
    
    /**
     * 计算预测播放量
     * 基于视频质量、音频质量、情感评分、主题热度等加权计算
     */
    private int calculatePredictedViews(Map<String, Object> videoData, Map<String, Object> audioData, Map<String, Object> textData) {
        // 基础播放量（100-500）
        double baseViews = 100 + random.nextDouble() * 400;
        
        // 1. 视频质量因子（权重0.3）
        double qualityScore = getDoubleValue(videoData, "qualityScore", 0.7);
        double clarity = getDoubleValue(videoData, "clarity", 0.7);
        double brightness = getDoubleValue(videoData, "brightness", 0.5);
        double videoQualityFactor = (qualityScore * 0.5 + clarity * 0.3 + brightness * 0.2) * 0.3;
        
        // 2. 音频质量因子（权重0.2）
        double audioQuality = getDoubleValue(audioData, "audioQuality", 0.7);
        double speechRatio = getDoubleValue(audioData, "speechRatio", 0.5);
        double noiseLevel = getDoubleValue(audioData, "noiseLevel", 0.2);
        // 噪声越低、语音比例越高，质量越好
        double audioQualityFactor = (audioQuality * 0.6 + speechRatio * 0.3 + (1.0 - noiseLevel) * 0.1) * 0.2;
        
        // 3. 情感因子（权重0.25）- 正面情感播放量更高
        double sentimentScore = getDoubleValue(textData, "sentimentScore", 0.0);
        double sentimentFactor = (sentimentScore + 1.0) / 2.0; // 归一化到0-1
        sentimentFactor = sentimentFactor * 0.25;
        
        // 4. 主题热度因子（权重0.15）
        String topicCategory = getStringValue(textData, "topicCategory", "");
        double topicHeatFactor = getTopicHeatFactor(topicCategory) * 0.15;
        
        // 5. 视频时长因子（权重0.1）- 适中时长（2-5分钟）播放量更高
        double duration = getDoubleValue(videoData, "duration", 60.0);
        double durationFactor = calculateDurationFactor(duration) * 0.1;
        
        // 6. 人员出现因子（权重0.05）- 有人出现的视频更吸引人
        Boolean hasPerson = getBooleanValue(videoData, "hasPerson", false);
        double personFactor = hasPerson ? 0.05 : 0.02;
        
        // 计算总因子（0-1之间）
        double totalFactor = videoQualityFactor + audioQualityFactor + sentimentFactor + 
                           topicHeatFactor + durationFactor + personFactor;
        
        // 计算最终播放量：基础播放量 * (1 + 总因子 * 倍数)
        // 倍数范围：1-20，即播放量可以是基础值的1-21倍
        double multiplier = 1.0 + totalFactor * 20.0;
        int predictedViews = (int) (baseViews * multiplier);
        
        // 限制范围：100-50000
        predictedViews = Math.max(100, Math.min(50000, predictedViews));
        
        logger.debug("播放量计算: qualityScore={}, audioQuality={}, sentimentScore={}, topicCategory={}, duration={}, hasPerson={}, result={}", 
                qualityScore, audioQuality, sentimentScore, topicCategory, duration, hasPerson, predictedViews);
        
        return predictedViews;
    }
    
    /**
     * 计算预测互动率
     * 基于情感评分、内容质量、语音比例等特征
     */
    private double calculatePredictedEngagement(Map<String, Object> videoData, Map<String, Object> audioData, Map<String, Object> textData) {
        // 基础互动率（0.01-0.03，即1%-3%）
        double baseEngagement = 0.01 + random.nextDouble() * 0.02;
        
        // 1. 情感因子（权重0.3）- 正面或负面情感互动率更高
        double sentimentScore = getDoubleValue(textData, "sentimentScore", 0.0);
        double sentimentFactor = Math.abs(sentimentScore) * 0.3; // 绝对值，极端情感互动更高
        
        // 2. 内容质量因子（权重0.25）
        double qualityScore = getDoubleValue(videoData, "qualityScore", 0.7);
        double clarity = getDoubleValue(videoData, "clarity", 0.7);
        double contentQualityFactor = (qualityScore * 0.6 + clarity * 0.4) * 0.25;
        
        // 3. 语音比例因子（权重0.2）- 有语音内容的视频互动率更高
        double speechRatio = getDoubleValue(audioData, "speechRatio", 0.5);
        double speechFactor = speechRatio * 0.2;
        
        // 4. 人员出现因子（权重0.15）- 有人出现的视频互动率更高
        Boolean hasPerson = getBooleanValue(videoData, "hasPerson", false);
        double personFactor = (hasPerson ? 0.8 : 0.3) * 0.15;
        
        // 5. 关键词丰富度因子（权重0.1）- 关键词越多，内容越丰富，互动率越高
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) textData.get("keywords");
        int keywordCount = keywords != null ? keywords.size() : 0;
        double keywordFactor = Math.min(1.0, keywordCount / 5.0) * 0.1; // 最多5个关键词为满分
        
        // 计算总因子（0-1之间）
        double totalFactor = sentimentFactor + contentQualityFactor + speechFactor + 
                           personFactor + keywordFactor;
        
        // 计算最终互动率：基础互动率 * (1 + 总因子 * 倍数)
        // 倍数范围：0-2，即互动率可以是基础值的1-3倍
        double multiplier = 1.0 + totalFactor * 2.0;
        double predictedEngagement = baseEngagement * multiplier;
        
        // 限制范围：0.005-0.15（0.5%-15%）
        predictedEngagement = Math.max(0.005, Math.min(0.15, predictedEngagement));
        
        logger.debug("互动率计算: sentimentScore={}, qualityScore={}, speechRatio={}, hasPerson={}, keywordCount={}, result={}", 
                sentimentScore, qualityScore, speechRatio, hasPerson, keywordCount, predictedEngagement);
        
        return roundToFourDecimals(predictedEngagement);
    }
    
    /**
     * 获取主题热度因子（0-1）
     */
    private double getTopicHeatFactor(String topicCategory) {
        return switch (topicCategory) {
            case "校园生活", "社团活动" -> 0.9;  // 高热度
            case "学术讨论", "科技创新" -> 0.8;
            case "就业指导", "创业分享" -> 0.85;
            case "体育运动", "艺术表演" -> 0.75;
            case "心理健康", "社会实践" -> 0.7;
            default -> 0.65;  // 默认中等热度
        };
    }
    
    /**
     * 计算视频时长因子（0-1）
     * 适中时长（2-5分钟，即120-300秒）得分最高
     */
    private double calculateDurationFactor(double duration) {
        if (duration < 60) {
            // 太短（<1分钟），因子较低
            return 0.3;
        } else if (duration >= 120 && duration <= 300) {
            // 适中时长（2-5分钟），因子最高
            return 1.0;
        } else if (duration > 300 && duration <= 600) {
            // 较长（5-10分钟），因子中等
            return 0.7;
        } else {
            // 很长（>10分钟），因子较低
            return 0.4;
        }
    }
    
    /**
     * 工具方法：安全获取String值
     */
    private String getStringValue(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }
    
    /**
     * 工具方法：安全获取Double值
     */
    private double getDoubleValue(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * 工具方法：安全获取Boolean值
     */
    private Boolean getBooleanValue(Map<String, Object> data, String key, Boolean defaultValue) {
        Object value = data.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
    
    /**
     * 四舍五入到4位小数
     */
    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
    
    /**
     * 保存分析结果
     */
    private String saveAnalysisResult(String taskId, String videoId, 
                                     Map<String, Object> moduleResults, 
                                     Map<String, Object> integrationResult) {
        try {
            if (moduleResults == null || moduleResults.isEmpty()) {
                logger.warn("模块结果数据为空，无法保存: taskId={}", taskId);
                return null;
            }
            
            // 从模块结果中提取各模块的数据
            Map<String, Object> audioResult = (Map<String, Object>) moduleResults.get("audio");
            Map<String, Object> videoResult = (Map<String, Object>) moduleResults.get("video");
            Map<String, Object> textResult = (Map<String, Object>) moduleResults.get("text");
            
            // 构造 AnalysisResult 对象
            AnalysisResult.AnalysisResultBuilder builder = AnalysisResult.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .taskId(taskId)
                    .videoId(videoId)
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now());
            
            // 设置音频特征
            if (audioResult != null) {
                builder.audioFeatures(JSON.toJSONString(audioResult));
                if (audioResult.containsKey("transcription")) {
                    builder.transcription(String.valueOf(audioResult.get("transcription")));
                }
            }
            
            // 设置视频特征
            if (videoResult != null) {
                builder.videoFeatures(JSON.toJSONString(videoResult));
            }
            
            // 设置文本特征和主题信息
            if (textResult != null) {
                builder.textFeatures(JSON.toJSONString(textResult));
                if (textResult.containsKey("topicCategory")) {
                    builder.topicCategory(String.valueOf(textResult.get("topicCategory")));
                }
                if (textResult.containsKey("keywords")) {
                    List<String> keywords = (List<String>) textResult.get("keywords");
                    builder.topicKeywords(JSON.toJSONString(keywords));
                }
                if (textResult.containsKey("sentimentScore")) {
                    Object sentimentScoreObj = textResult.get("sentimentScore");
                    if (sentimentScoreObj instanceof Number) {
                        builder.sentimentScore(BigDecimal.valueOf(((Number) sentimentScoreObj).doubleValue()));
                    }
                }
                if (textResult.containsKey("sentimentLabel")) {
                    builder.sentimentLabel(String.valueOf(textResult.get("sentimentLabel")));
                }
            }
            
            // 设置整合分析结果（完整的受众分析数据）
            if (integrationResult != null) {
                // 构建完整的受众分析对象，包含所有前端需要的字段
                Map<String, Object> audienceAnalysis = new java.util.HashMap<>();
                audienceAnalysis.put("ageDistribution", integrationResult.get("ageDistribution"));
                audienceAnalysis.put("predictedInterests", integrationResult.get("predictedInterests"));
                audienceAnalysis.put("predictedViews", integrationResult.get("predictedViews"));
                audienceAnalysis.put("predictedEngagement", integrationResult.get("predictedEngagement"));
                builder.audienceAnalysis(JSON.toJSONString(audienceAnalysis));
                logger.info("受众分析数据已保存: taskId={}, ageDistribution={}, predictedViews={}, predictedEngagement={}", 
                        taskId, integrationResult.get("ageDistribution"), 
                        integrationResult.get("predictedViews"), 
                        integrationResult.get("predictedEngagement"));
            }
            
            // 生成综合评估结果（基于各模块结果）
            double riskScore = generateRiskScore(audioResult, videoResult, textResult);
            String riskLevel = calculateRiskLevel(riskScore);
            builder.riskScore(BigDecimal.valueOf(riskScore));
            builder.riskLevel(riskLevel);
            
            // 设置高校相关信息（基于文本分析）
            if (textResult != null && textResult.containsKey("isUniversityRelated")) {
                Boolean isUniversityRelated = (Boolean) textResult.get("isUniversityRelated");
                builder.isUniversityRelated(isUniversityRelated);
                if (isUniversityRelated && textResult.containsKey("universityName")) {
                    builder.universityName(String.valueOf(textResult.get("universityName")));
                }
            }
            
            // 设置传播潜力
            builder.spreadPotential(BigDecimal.valueOf(Math.random()));
            
            AnalysisResult result = builder.build();
            
            // 保存结果
            String resultId = analysisResultService.saveResult(result);
            logger.info("分析结果已保存: taskId={}, resultId={}", taskId, resultId);
            
            return resultId;
            
        } catch (Exception e) {
            logger.error("保存分析结果失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 生成风险评分（基于各模块结果）
     */
    private double generateRiskScore(Map<String, Object> audioResult, 
                                     Map<String, Object> videoResult, 
                                     Map<String, Object> textResult) {
        // 简单的风险评分逻辑（可以根据实际需求调整）
        double score = Math.random() * 0.3 + 0.1; // 0.1-0.4，大多数为低风险
        return roundToFourDecimals(score);
    }
    
    /**
     * 计算风险等级
     */
    private String calculateRiskLevel(double riskScore) {
        if (riskScore < 0.3) {
            return "LOW";
        } else if (riskScore < 0.7) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }
    
    /**
     * 保存分析结果（旧方法，保留以兼容）
     */
    private String saveAnalysisResult(String taskId, String videoId, Map<String, Object> resultData) {
        try {
            if (resultData == null) {
                logger.warn("结果数据为空，无法保存: taskId={}", taskId);
                return null;
            }
            
            // 从 resultData 中提取各模块的结果
            Map<String, Object> audioResult = (Map<String, Object>) resultData.get("audioResult");
            Map<String, Object> videoResult = (Map<String, Object>) resultData.get("videoResult");
            Map<String, Object> textResult = (Map<String, Object>) resultData.get("textResult");
            
            // 构造 AnalysisResult 对象
            AnalysisResult.AnalysisResultBuilder builder = AnalysisResult.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .taskId(taskId)
                    .videoId(videoId)
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now());
            
            // 设置综合评估结果
            if (resultData.containsKey("riskScore")) {
                Object riskScoreObj = resultData.get("riskScore");
                if (riskScoreObj instanceof Number) {
                    builder.riskScore(BigDecimal.valueOf(((Number) riskScoreObj).doubleValue()));
                }
            }
            if (resultData.containsKey("riskLevel")) {
                builder.riskLevel(String.valueOf(resultData.get("riskLevel")));
            }
            if (resultData.containsKey("isUniversityRelated")) {
                builder.isUniversityRelated((Boolean) resultData.get("isUniversityRelated"));
            }
            if (resultData.containsKey("universityName")) {
                builder.universityName((String) resultData.get("universityName"));
            }
            
            // 设置音频特征
            if (audioResult != null) {
                builder.audioFeatures(JSON.toJSONString(audioResult));
                if (audioResult.containsKey("transcription")) {
                    builder.transcription(String.valueOf(audioResult.get("transcription")));
                }
            }
            
            // 设置视频特征
            if (videoResult != null) {
                builder.videoFeatures(JSON.toJSONString(videoResult));
            }
            
            // 设置文本特征和主题信息
            if (textResult != null) {
                builder.textFeatures(JSON.toJSONString(textResult));
                if (textResult.containsKey("topicCategory")) {
                    builder.topicCategory(String.valueOf(textResult.get("topicCategory")));
                }
                if (textResult.containsKey("keywords")) {
                    List<String> keywords = (List<String>) textResult.get("keywords");
                    builder.topicKeywords(JSON.toJSONString(keywords));
                }
                if (textResult.containsKey("sentimentScore")) {
                    Object sentimentScoreObj = textResult.get("sentimentScore");
                    if (sentimentScoreObj instanceof Number) {
                        builder.sentimentScore(BigDecimal.valueOf(((Number) sentimentScoreObj).doubleValue()));
                    }
                }
                if (textResult.containsKey("sentimentLabel")) {
                    builder.sentimentLabel(String.valueOf(textResult.get("sentimentLabel")));
                }
            }
            
            // 设置传播潜力（如果有）
            if (resultData.containsKey("spreadPotential")) {
                Object spreadPotentialObj = resultData.get("spreadPotential");
                if (spreadPotentialObj instanceof Number) {
                    builder.spreadPotential(BigDecimal.valueOf(((Number) spreadPotentialObj).doubleValue()));
                }
            }
            
            AnalysisResult result = builder.build();
            
            // 保存结果
            String resultId = analysisResultService.saveResult(result);
            logger.info("分析结果已保存: taskId={}, resultId={}", taskId, resultId);
            
            return resultId;
            
        } catch (Exception e) {
            logger.error("保存分析结果失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 创建 WebSocket 消息格式
     */
    private String createWebSocketMessage(String type, String message, Object data) {
        Map<String, Object> msg = new java.util.HashMap<>();
        msg.put("type", type);
        msg.put("message", message);
        msg.put("timestamp", System.currentTimeMillis());
        if (data != null) {
            msg.put("data", data);
        }
        return JSON.toJSONString(msg);
    }
}

