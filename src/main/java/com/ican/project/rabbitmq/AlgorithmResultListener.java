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
            String moduleType = jsonMessage.getString("moduleType"); // 音频/视频/文本
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
            
            // 更新任务进度
            analysisTaskService.updateTaskStatus(taskId, 
                    AnalysisTask.Status.PROCESSING.name(), progress, null);
            
            // 构造进度消息
            String progressMessage = String.format("%s分析完成", moduleType);
            
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
            
            // 如果进度达到 100%，表示所有模块都完成了
            if (progress >= 100) {
                logger.info("任务分析完成: taskId={}", taskId);
                
                // 保存分析结果
                String resultId = saveAnalysisResult(taskId, videoId, resultData);
                
                // 标记任务完成
                analysisTaskService.markTaskCompleted(taskId);
                
                // 发送完成通知
                if (userId != null) {
                    TaskProgressWebSocket.sendTaskCompleted(userId, taskId, videoId, resultId);
                }
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
     * 保存分析结果
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

