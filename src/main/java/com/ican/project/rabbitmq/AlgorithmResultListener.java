package com.ican.project.rabbitmq;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ican.project.config.RabbitMQConfig;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.MinioService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 结果回调队列监听器（全新架构：4个模块）
 * 实现双向异步事件驱动架构的Java端Consumer
 * 
 * 工作流程：
 * 1. 监听结果回调队列（Python -> Java）
 * 2. 使用Redis Set无状态跟踪已完成的模块（video, audio, text, integration）
 * 3. 动态计算进度（1个=25%, 2个=50%, 3个=75%, 4个=90%）
 * 4. 当4个模块都完成时，执行最终总结（Java），推送100%进度
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
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private MinioService minioService;
    
    /**
     * Redis Key 前缀：存储任务已完成的模块
     */
    private static final String REDIS_KEY_PREFIX = "analysis:modules:";
    
    /**
     * Redis Key 前缀：存储任务模块结果数据
     */
    private static final String REDIS_RESULT_KEY_PREFIX = "analysis:results:";
    
    /**
     * Redis Key 过期时间（小时）
     */
    private static final long REDIS_KEY_EXPIRE_HOURS = 24;
    
    /**
     * 监听算法分析结果队列
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
            String moduleType = jsonMessage.getString("moduleType");
            @SuppressWarnings("unchecked")
            Map<String, Object> resultData = jsonMessage.getObject("resultData", Map.class);
            
            if (taskId == null || moduleType == null || resultData == null) {
                logger.error("消息格式错误，缺少必要字段");
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            
            // 验证模块类型（4个模块）
            if (!"audio".equals(moduleType) && !"video".equals(moduleType) && 
                !"text".equals(moduleType) && !"integration".equals(moduleType)) {
                logger.error("无效的模块类型: {}", moduleType);
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            
            // 获取任务信息
            AnalysisTask task = analysisTaskMapper.selectById(taskId);
            if (task == null) {
                logger.error("任务不存在: taskId={}", taskId);
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            
            String userId = task.getUserId();
            String videoId = task.getVideoId();
            
            // ========== Redis状态管理 ==========
            // 1. 存储模块结果数据
            String resultKey = REDIS_RESULT_KEY_PREFIX + taskId + ":" + moduleType;
            redisTemplate.opsForValue().set(resultKey, resultData, REDIS_KEY_EXPIRE_HOURS, TimeUnit.HOURS);
            
            // 2. 跟踪已完成的模块
            String modulesKey = REDIS_KEY_PREFIX + taskId;
            redisTemplate.opsForSet().add(modulesKey, moduleType);
            redisTemplate.expire(modulesKey, REDIS_KEY_EXPIRE_HOURS, TimeUnit.HOURS);
            
            // 3. 获取已完成模块数量
            Long completedCount = redisTemplate.opsForSet().size(modulesKey);
            if (completedCount == null) {
                completedCount = 0L;
            }
            
            // 4. 动态计算进度
            int progress = switch (completedCount.intValue()) {
                case 1 -> 25;
                case 2 -> 50;
                case 3 -> 75;
                case 4 -> 90;
                default -> 0;
            };
            
            logger.info("任务模块状态更新: taskId={}, moduleType={}, 已完成={}个, 进度={}%", 
                    taskId, moduleType, completedCount, progress);
            
            // 更新任务进度
            analysisTaskService.updateTaskStatus(taskId, 
                    AnalysisTask.Status.PROCESSING.name(), progress, null);
            
            // 推送进度到前端
            if (userId != null) {
                Map<String, Object> wsData = new java.util.HashMap<>();
                wsData.put("taskId", taskId);
                wsData.put("videoId", videoId);
                wsData.put("status", AnalysisTask.Status.PROCESSING.name());
                wsData.put("progress", progress);
                wsData.put("message", getModuleTypeDisplay(moduleType) + "分析完成");
                wsData.put("moduleType", moduleType);
                wsData.put("resultData", resultData);
                
                TaskProgressWebSocket.sendMessage(userId, createWebSocketMessage("task_progress", "任务进度更新", wsData));
                logger.info("已转发进度消息: userId={}, progress={}%", userId, progress);
            }
            
            // ========== 最终总结触发 ==========
            if (completedCount == 4) {
                logger.info("========== 检测到4个模块全部完成，执行最终总结 ========== taskId={}", taskId);
                
                // 从Redis获取4个模块结果
                Map<String, Object> moduleResults = new java.util.HashMap<>();
                Object audioResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":audio");
                Object videoResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":video");
                Object textResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":text");
                Object integrationResult = redisTemplate.opsForValue().get(REDIS_RESULT_KEY_PREFIX + taskId + ":integration");
                
                if (audioResult instanceof Map) {
                    moduleResults.put("audio", (Map<String, Object>) audioResult);
                }
                if (videoResult instanceof Map) {
                    moduleResults.put("video", (Map<String, Object>) videoResult);
                }
                if (textResult instanceof Map) {
                    moduleResults.put("text", (Map<String, Object>) textResult);
                }
                if (integrationResult instanceof Map) {
                    moduleResults.put("integration", (Map<String, Object>) integrationResult);
                }
                
                // 执行最终总结
                String resultId = performFinalSummary(taskId, videoId, userId, moduleResults);
                
                // 更新进度为100%
                analysisTaskService.updateTaskStatus(taskId, 
                        AnalysisTask.Status.PROCESSING.name(), 100, null);
                
                // 标记任务完成
                analysisTaskService.markTaskCompleted(taskId);
                
                // 推送100%进度
                if (userId != null) {
                    Map<String, Object> finalWsData = new java.util.HashMap<>();
                    finalWsData.put("taskId", taskId);
                    finalWsData.put("videoId", videoId);
                    finalWsData.put("status", AnalysisTask.Status.COMPLETED.name());
                    finalWsData.put("progress", 100);
                    finalWsData.put("message", "分析完成");
                    finalWsData.put("moduleType", "final");
                    
                    TaskProgressWebSocket.sendMessage(userId, createWebSocketMessage("task_progress", "任务进度更新", finalWsData));
                    TaskProgressWebSocket.sendTaskCompleted(userId, taskId, videoId, resultId);
                    
                    logger.info("最终总结完成: resultId={}, progress=100%", resultId);
                }
                
                // 清理Redis
                redisTemplate.delete(modulesKey);
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":audio");
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":video");
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":text");
                redisTemplate.delete(REDIS_RESULT_KEY_PREFIX + taskId + ":integration");
                logger.info("已清理Redis数据: taskId={}", taskId);
            }
            
            // 确认消息
            channel.basicAck(deliveryTag, false);
            
        } catch (Exception e) {
            logger.error("处理消息失败: {}", e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, true);
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
            case "integration" -> "综合";
            default -> moduleType;
        };
    }
    
    /**
     * 执行最终总结（Java快速处理）
     */
    @SuppressWarnings("unchecked")
    private String performFinalSummary(String taskId, String videoId, String userId, Map<String, Object> moduleResults) {
        logger.info("========== 开始执行最终总结 ========== taskId={}", taskId);
        
        try {
            // 提取各模块结果
            Map<String, Object> videoResult = (Map<String, Object>) moduleResults.get("video");
            Map<String, Object> audioResult = (Map<String, Object>) moduleResults.get("audio");
            Map<String, Object> textResult = (Map<String, Object>) moduleResults.get("text");
            Map<String, Object> integrationResult = (Map<String, Object>) moduleResults.get("integration");
            
            if (videoResult == null || audioResult == null || textResult == null || integrationResult == null) {
                logger.error("模块结果不完整");
                return null;
            }
            
            // 提取子数据
            Map<String, Object> videoFeatures = (Map<String, Object>) videoResult.get("features");
            Map<String, Object> textVideoInfo = (Map<String, Object>) textResult.get("videoInfo");
            Map<String, Object> preliminaryEvidences = (Map<String, Object>) textResult.get("preliminaryEvidences");
            Map<String, Object> integrationIdentity = (Map<String, Object>) integrationResult.get("identity");
            Map<String, Object> integrationUniversity = (Map<String, Object>) integrationResult.get("university");
            Map<String, Object> integrationTopic = (Map<String, Object>) integrationResult.get("topic");
            Map<String, Object> integrationOpinionRisk = (Map<String, Object>) integrationResult.get("opinionRisk");
            Map<String, Object> integrationAction = (Map<String, Object>) integrationResult.get("action");
            Map<String, Object> integrationTimelineData = (Map<String, Object>) integrationResult.get("timelineData");
            
            // 构建AnalysisResult对象
            AnalysisResult.AnalysisResultBuilder builder = AnalysisResult.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .taskId(taskId)
                    .videoId(videoId)
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now());
            
            // 1. 视频基本信息（优先从Video表获取videoUrl）
            Video video = videoMapper.selectById(videoId);
            String videoUrl = "";
            if (video != null && video.getFilePath() != null) {
                videoUrl = minioService.getFileUrl(video.getFilePath());
            } else {
                videoUrl = getStringValue(videoFeatures, "videoPath", "");
            }
            
            // 更新Video表的duration（如果尚未设置）
            if (video != null && (video.getDuration() == null || video.getDuration() == 0.0)) {
                Object durationObj = videoFeatures.get("duration");
                if (durationObj != null) {
                    Double duration = ((Number) durationObj).doubleValue();
                    video.setDuration(duration);
                    videoMapper.updateById(video);
                    logger.info("已更新Video表duration: videoId={}, duration={}", videoId, duration);
                }
            }
            
            builder.videoUrl(videoUrl);
            builder.aiDescription(getStringValue(textVideoInfo, "description", ""));
            builder.detectedKeywords(JSON.toJSONString(textVideoInfo.get("detectedKeywords")));
            builder.mainCharacter(JSON.toJSONString(videoFeatures.get("mainCharacter")));
            
            // 2. 核心分析维度
            builder.identityLabel(getStringValue(integrationIdentity, "identityLabel", ""));
            builder.identityEvidences(JSON.toJSONString(preliminaryEvidences.get("identity")));
            builder.identityFusion(JSON.toJSONString(integrationIdentity.get("modalityFusion")));
            
            builder.universityName(getStringValue(integrationUniversity, "universityName", ""));
            builder.universityEvidences(JSON.toJSONString(preliminaryEvidences.get("university")));
            builder.universityFusion(JSON.toJSONString(integrationUniversity.get("modalityFusion")));
            
            builder.topicCategory(getStringValue(integrationTopic, "topicCategory", ""));
            builder.topicSubCategory(getStringValue(integrationTopic, "topicSubCategory", ""));
            builder.topicEvidences(JSON.toJSONString(preliminaryEvidences.get("topic")));
            builder.topicFusion(JSON.toJSONString(integrationTopic.get("modalityFusion")));
            
            builder.attitudeEvidences(JSON.toJSONString(preliminaryEvidences.get("attitude")));
            
            builder.opinionRiskReason(getStringValue(integrationOpinionRisk, "riskReason", ""));
            builder.opinionRiskEvidences(JSON.toJSONString(preliminaryEvidences.get("opinionRisk")));
            builder.opinionRiskFusion(JSON.toJSONString(integrationOpinionRisk.get("modalityFusion")));
            
            builder.actionSuggestion(getStringValue(integrationAction, "actionSuggestion", ""));
            builder.actionDetail(getStringValue(integrationAction, "actionDetail", ""));
            builder.actionEvidences(JSON.toJSONString(preliminaryEvidences.get("action")));
            builder.actionFusion(JSON.toJSONString(integrationAction.get("modalityFusion")));
            
            // 3. 时间轴数据
            builder.timeGranularity(5);
            builder.videoRisks(JSON.toJSONString(videoResult.get("videoRisks")));
            builder.audioEmotions(JSON.toJSONString(audioResult.get("audioEmotions")));
            builder.textRisks(JSON.toJSONString(textResult.get("textRisks")));
            builder.comprehensiveRisks(JSON.toJSONString(integrationTimelineData.get("comprehensiveRisks")));
            builder.radarByTime(JSON.toJSONString(integrationTimelineData.get("radarByTime")));
            builder.averageRadarData(JSON.toJSONString(integrationTimelineData.get("averageRadarData")));
            
            // 4. 全模态智能事件流（合并后按时间排序）
            List<Map<String, Object>> allTimelineEvents = new java.util.ArrayList<>();
            if (videoResult.get("visualEvents") instanceof List) {
                ((List<Object>) videoResult.get("visualEvents")).forEach(e -> {
                    if (e instanceof Map) {
                        allTimelineEvents.add((Map<String, Object>) e);
                    }
                });
            }
            if (audioResult.get("audioEffectEvents") instanceof List) {
                ((List<Object>) audioResult.get("audioEffectEvents")).forEach(e -> {
                    if (e instanceof Map) {
                        allTimelineEvents.add((Map<String, Object>) e);
                    }
                });
            }
            if (textResult.get("speechEvents") instanceof List) {
                ((List<Object>) textResult.get("speechEvents")).forEach(e -> {
                    if (e instanceof Map) {
                        allTimelineEvents.add((Map<String, Object>) e);
                    }
                });
            }
            
            // 按startTime排序（关键：让事件流混合显示）
            allTimelineEvents.sort((e1, e2) -> {
                Object t1 = e1.get("startTime");
                Object t2 = e2.get("startTime");
                int time1 = (t1 instanceof Number) ? ((Number) t1).intValue() : 0;
                int time2 = (t2 instanceof Number) ? ((Number) t2).intValue() : 0;
                return Integer.compare(time1, time2);
            });
            
            builder.timelineEvents(JSON.toJSONString(allTimelineEvents));
            
            // 5. 场景识别
            builder.sceneRecognition(JSON.toJSONString(videoResult.get("sceneRecognition")));
            
            AnalysisResult result = builder.build();
            
            // 保存结果
            String resultId = analysisResultService.saveResult(result);
            logger.info("最终总结完成，已保存: taskId={}, resultId={}", taskId, resultId);
            
            return resultId;
            
        } catch (Exception e) {
            logger.error("执行最终总结失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 工具方法：安全获取String值
     */
    private String getStringValue(Map<String, Object> data, String key, String defaultValue) {
        if (data == null) return defaultValue;
        Object value = data.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }
    
    /**
     * 创建WebSocket消息格式
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
