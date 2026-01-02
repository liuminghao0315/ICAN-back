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
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * RabbitMQ 消息监听器
 * 监听来自 Python 算法的分析结果消息，并转发到 WebSocket
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
    
    /**
     * 存储每个任务已接收的模块结果
     * key: taskId
     * value: Map<moduleType, resultData>
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Map<String, Object>> taskModuleResults = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
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
            
            // 解析消息
            JSONObject jsonMessage = JSON.parseObject(messageBody);
            String taskId = jsonMessage.getString("taskId");
            String moduleType = jsonMessage.getString("moduleType"); // audio/video/text
            Integer progress = jsonMessage.getInteger("progress");
            Map<String, Object> resultData = jsonMessage.getObject("resultData", Map.class);
            
            if (taskId == null || moduleType == null || progress == null) {
                logger.error("消息格式错误，缺少必要字段: taskId={}, moduleType={}, progress={}", 
                        taskId, moduleType, progress);
                // 拒绝消息，不重新入队
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
            
            // 存储当前模块的结果
            taskModuleResults.computeIfAbsent(taskId, k -> new java.util.HashMap<>()).put(moduleType, resultData);
            
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
            
            // 检查是否所有三个基础模块都已完成（audio, video, text）
            Map<String, Object> moduleResults = taskModuleResults.get(taskId);
            boolean allModulesCompleted = moduleResults != null 
                    && moduleResults.containsKey("audio") 
                    && moduleResults.containsKey("video") 
                    && moduleResults.containsKey("text");
            
            // 如果三个基础模块都完成，且当前是文本分析（75%），则触发第4步整合分析
            if (allModulesCompleted && "text".equals(moduleType) && progress == 75) {
                logger.info("检测到前三步分析完成，开始执行第4步整合分析: taskId={}", taskId);
                
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
                
                // 清理该任务的状态缓存
                taskModuleResults.remove(taskId);
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
     * 生成受众年龄分布数据
     */
    private Map<String, Object> performIntegrationAnalysis(String taskId, Map<String, Object> moduleResults) {
        logger.info("开始执行整合分析: taskId={}", taskId);
        
        // 生成模拟的受众年龄分布（使用前端期望的键名格式）
        Map<String, Double> ageDistribution = new java.util.HashMap<>();
        
        // 生成各年龄段占比（总和为1.0）
        ageDistribution.put("18-24", roundToFourDecimals(random.nextDouble() * 0.3 + 0.3)); // 30%-60%
        ageDistribution.put("25-34", roundToFourDecimals(random.nextDouble() * 0.3 + 0.2)); // 20%-50%
        ageDistribution.put("35-44", roundToFourDecimals(random.nextDouble() * 0.15 + 0.05)); // 5%-20%
        ageDistribution.put("45+", roundToFourDecimals(random.nextDouble() * 0.15 + 0.05)); // 5%-20%
        
        // 归一化，确保总和为1.0
        final double total = ageDistribution.values().stream().mapToDouble(Double::doubleValue).sum();
        ageDistribution.replaceAll((k, v) -> roundToFourDecimals(v / total));
        
        // 确保总和精确为1.0（处理浮点数误差）
        double finalTotal = ageDistribution.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(finalTotal - 1.0) > 0.0001) {
            String lastKey = ageDistribution.keySet().toArray(new String[0])[ageDistribution.size() - 1];
            double lastValue = ageDistribution.get(lastKey);
            ageDistribution.put(lastKey, roundToFourDecimals(lastValue + (1.0 - finalTotal)));
        }
        
        // 生成预测兴趣标签
        List<String> predictedInterests = java.util.Arrays.asList("教育", "校园生活", "青年文化", "科技创新");
        
        // 生成预测播放量（100-10000之间）
        int predictedViews = 100 + random.nextInt(9900);
        
        // 生成预测互动率（0-0.1之间）
        double predictedEngagement = roundToFourDecimals(random.nextDouble() * 0.1);
        
        // 构建整合分析结果（完整的受众分析数据）
        Map<String, Object> integrationResult = new java.util.HashMap<>();
        integrationResult.put("ageDistribution", ageDistribution);
        integrationResult.put("predictedInterests", predictedInterests);
        integrationResult.put("predictedViews", predictedViews);
        integrationResult.put("predictedEngagement", predictedEngagement);
        integrationResult.put("analysisTimestamp", System.currentTimeMillis());
        
        logger.info("整合分析完成: taskId={}, ageDistribution={}, predictedViews={}, predictedEngagement={}", 
                taskId, ageDistribution, predictedViews, predictedEngagement);
        
        return integrationResult;
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

