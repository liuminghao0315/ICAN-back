package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.AnalysisTaskDTO;
import com.ican.project.model.dto.UrlImportTaskDTO;
import com.ican.project.model.vo.AnalysisTaskVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.VideoDownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 分析任务控制器
 */
@RestController
@RequestMapping("/api/analysis/task")
@Tag(name = "分析任务管理", description = "视频分析任务创建、查询、取消等接口")
public class AnalysisTaskController {
    
    private static final Logger logger = LoggerFactory.getLogger(AnalysisTaskController.class);
    
    @Autowired
    private AnalysisTaskService analysisTaskService;
    
    @Autowired
    private VideoDownloadService videoDownloadService;
    
    /**
     * 创建分析任务
     */
    @PostMapping
    @Operation(summary = "创建分析任务", description = "为指定视频创建分析任务")
    public Result<AnalysisTaskVO> createTask(
            @Valid @RequestBody AnalysisTaskDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("创建分析任务: videoId={}, taskType={}", dto.getVideoId(), dto.getTaskType());
        
        AnalysisTaskVO result = analysisTaskService.createTask(dto.trimMe(), userDetails.getUserId());
        
        return Result.success("任务创建成功", result);
    }
    
    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取任务详情", description = "根据任务ID获取任务详细信息")
    public Result<AnalysisTaskVO> getTask(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        AnalysisTaskVO result = analysisTaskService.getTaskById(taskId, userDetails.getUserId());
        
        return Result.success(result);
    }
    
    /**
     * 获取视频的最新任务
     */
    @GetMapping("/video/{videoId}")
    @Operation(summary = "获取视频的最新任务", description = "获取指定视频的最新分析任务")
    public Result<AnalysisTaskVO> getVideoLatestTask(
            @Parameter(description = "视频ID") @PathVariable String videoId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        AnalysisTaskVO result = analysisTaskService.getLatestTaskByVideoId(videoId, userDetails.getUserId());
        
        if (result == null) {
            return Result.success("该视频暂无分析任务", null);
        }
        
        return Result.success(result);
    }
    
    /**
     * 获取我的任务列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取我的任务列表", description = "分页获取当前用户的分析任务列表")
    public Result<Page<AnalysisTaskVO>> getMyTasks(
            @Parameter(description = "状态筛选（可选）: PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED") 
            @RequestParam(required = false) String status,
            @Parameter(description = "风险等级筛选（可选）: LOW/MEDIUM/HIGH") 
            @RequestParam(required = false) String riskLevel,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "排序字段: gmtCreated, riskScore, videoDuration") @RequestParam(defaultValue = "gmtCreated") String sortBy,
            @Parameter(description = "排序方向: asc, desc") @RequestParam(defaultValue = "desc") String sortOrder,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        Page<AnalysisTaskVO> result = analysisTaskService.getUserTasks(
                userDetails.getUserId(), status, riskLevel, page, size, sortBy, sortOrder);
        
        return Result.success(result);
    }
    
    /**
     * 取消任务
     */
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务", description = "取消指定的分析任务（仅限等待中或处理中的任务）")
    public Result<Void> cancelTask(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("取消分析任务: taskId={}", taskId);
        
        analysisTaskService.cancelTask(taskId, userDetails.getUserId());
        
        return Result.success("任务已取消", null);
    }
    
    /**
     * 重试任务
     */
    @PostMapping("/{taskId}/retry")
    @Operation(summary = "重试任务", description = "重新执行失败或已取消的任务")
    public Result<AnalysisTaskVO> retryTask(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("重试分析任务: taskId={}", taskId);
        
        AnalysisTaskVO result = analysisTaskService.retryTask(taskId, userDetails.getUserId());
        
        return Result.success("新任务创建成功", result);
    }
    
    /**
     * URL导入创建分析任务
     */
    @PostMapping("/url-import")
    @Operation(summary = "URL导入创建分析任务", description = "通过外部URL导入视频并自动创建分析任务")
    public Result<AnalysisTaskVO> createUrlImportTask(
            @Valid @RequestBody UrlImportTaskDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("URL导入创建任务: url={}", dto.getUrl());
        
        UrlImportTaskDTO trimmed = dto.trimMe();
        AnalysisTaskVO result = analysisTaskService.createUrlImportTask(
                trimmed.getUrl(), trimmed.getTitle(), trimmed.getTaskType(), userDetails.getUserId());
        
        // 异步启动视频下载
        videoDownloadService.downloadVideoAsync(
                trimmed.getUrl(), result.getVideoId(), result.getId(), userDetails.getUserId());
        
        return Result.success("任务创建成功，视频正在下载中", result);
    }
}

