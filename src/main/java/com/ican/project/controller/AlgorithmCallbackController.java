package com.ican.project.controller;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.AlgorithmCallbackDTO;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.websocket.TaskProgressWebSocket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 算法服务回调控制器
 * 用于接收真实算法服务的回调请求
 * 注意：此接口应在生产环境中增加安全验证（如API密钥验证）
 */
@RestController
@RequestMapping("/api/algorithm")
@Tag(name = "算法服务回调", description = "接收算法服务的分析结果回调")
public class AlgorithmCallbackController {
    
    private static final Logger logger = LoggerFactory.getLogger(AlgorithmCallbackController.class);
    
    @Autowired
    private AnalysisTaskService analysisTaskService;
    
    @Autowired
    private AnalysisResultService analysisResultService;
    
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    
    /**
     * 接收算法服务的回调
     * 当算法服务完成视频分析后，调用此接口提交结果
     */
    @PostMapping("/callback")
    @Operation(summary = "算法结果回调", description = "接收算法服务的分析结果")
    public Result<String> callback(@Valid @RequestBody AlgorithmCallbackDTO dto) {
        dto.trimMe();
        String taskId = dto.getTaskId();
        String status = dto.getStatus();
        
        logger.info("收到算法回调: taskId={}, status={}", taskId, status);
        
        // 验证任务存在
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            logger.warn("任务不存在: taskId={}", taskId);
            return Result.fail(404, "任务不存在");
        }
        
        String userId = task.getUserId();
        
        try {
            if ("completed".equals(status)) {
                // 处理成功的情况
                return handleSuccess(dto, task, userId);
            } else if ("failed".equals(status)) {
                // 处理失败的情况
                return handleFailure(dto, task, userId);
            } else {
                return Result.fail(400, "无效的状态: " + status);
            }
        } catch (Exception e) {
            logger.error("处理算法回调失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return Result.fail(500, "处理回调失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新任务进度（可选接口，用于实时进度更新）
     */
    @PostMapping("/progress")
    @Operation(summary = "更新任务进度", description = "算法服务更新任务处理进度")
    public Result<Void> updateProgress(
            @RequestParam String taskId,
            @RequestParam Integer progress,
            @RequestParam(required = false) String message) {
        
        logger.debug("更新任务进度: taskId={}, progress={}, message={}", taskId, progress, message);
        
        // 验证任务存在
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            return Result.fail(404, "任务不存在");
        }
        
        // 更新进度
        analysisTaskService.updateTaskStatus(taskId, 
                AnalysisTask.Status.PROCESSING.name(), progress, null);
        
        // 发送WebSocket通知
        String userId = task.getUserId();
        String videoId = task.getVideoId();
        if (userId != null) {
            TaskProgressWebSocket.sendTaskProgress(userId, taskId, videoId,
                    AnalysisTask.Status.PROCESSING.name(), progress, message);
        }
        
        return Result.success("进度更新成功", null);
    }
    
    /**
     * 处理成功回调
     */
    private Result<String> handleSuccess(AlgorithmCallbackDTO dto, AnalysisTask task, String userId) {
        String taskId = dto.getTaskId();
        
        // 构建分析结果
        AnalysisResult result = AnalysisResult.builder()
                .id(IdUtil.fastSimpleUUID())
                .taskId(taskId)
                .videoId(task.getVideoId())
                .riskScore(dto.getRiskScore())
                .riskLevel(dto.getRiskLevel())
                .isUniversityRelated(dto.getIsUniversityRelated())
                .universityName(dto.getUniversityName())
                .universityConfidence(dto.getUniversityConfidence())
                .topicCategory(dto.getTopicCategory())
                .topicKeywords(dto.getTopicKeywords() != null ? 
                        JSON.toJSONString(dto.getTopicKeywords()) : null)
                .sentimentScore(dto.getSentimentScore())
                .sentimentLabel(dto.getSentimentLabel())
                .videoFeatures(dto.getVideoFeatures() != null ? 
                        JSON.toJSONString(dto.getVideoFeatures()) : null)
                .audioFeatures(dto.getAudioFeatures() != null ? 
                        JSON.toJSONString(dto.getAudioFeatures()) : null)
                .transcription(dto.getTranscription())
                .textFeatures(dto.getTextFeatures() != null ? 
                        JSON.toJSONString(dto.getTextFeatures()) : null)
                .audienceAnalysis(dto.getAudienceAnalysis() != null ? 
                        JSON.toJSONString(dto.getAudienceAnalysis()) : null)
                .spreadPotential(dto.getSpreadPotential())
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        
        // 保存结果
        String resultId = analysisResultService.saveResult(result);
        
        // 标记任务完成
        analysisTaskService.markTaskCompleted(taskId);
        
        // 发送WebSocket通知
        if (userId != null) {
            TaskProgressWebSocket.sendTaskCompleted(userId, taskId, task.getVideoId(), resultId);
        }
        
        logger.info("算法回调处理成功: taskId={}, resultId={}", taskId, resultId);
        
        return Result.success("回调处理成功", resultId);
    }
    
    /**
     * 处理失败回调
     */
    private Result<String> handleFailure(AlgorithmCallbackDTO dto, AnalysisTask task, String userId) {
        String taskId = dto.getTaskId();
        String errorMessage = dto.getErrorMessage() != null ? dto.getErrorMessage() : "算法处理失败";
        
        // 标记任务失败
        analysisTaskService.markTaskFailed(taskId, errorMessage);
        
        // 发送WebSocket通知
        if (userId != null) {
            TaskProgressWebSocket.sendTaskFailed(userId, taskId, task.getVideoId(), errorMessage);
        }
        
        logger.info("算法回调处理失败通知: taskId={}, error={}", taskId, errorMessage);
        
        return Result.success("失败回调处理成功", null);
    }
}

