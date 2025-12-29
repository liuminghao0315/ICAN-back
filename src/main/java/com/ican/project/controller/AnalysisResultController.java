package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.common.Result;
import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.AnalysisResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 分析结果控制器
 */
@RestController
@RequestMapping("/api/analysis/result")
@Tag(name = "分析结果管理", description = "视频分析结果查询、统计等接口")
public class AnalysisResultController {
    
    private static final Logger logger = LoggerFactory.getLogger(AnalysisResultController.class);
    
    @Autowired
    private AnalysisResultService analysisResultService;
    
    /**
     * 获取分析结果详情
     */
    @GetMapping("/{resultId}")
    @Operation(summary = "获取分析结果详情", description = "根据结果ID获取分析结果详细信息")
    public Result<AnalysisResultVO> getResult(
            @Parameter(description = "结果ID") @PathVariable String resultId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        AnalysisResultVO result = analysisResultService.getResultById(resultId, userDetails.getUserId());
        
        return Result.success(result);
    }
    
    /**
     * 根据任务ID获取分析结果
     */
    @GetMapping("/task/{taskId}")
    @Operation(summary = "根据任务ID获取分析结果", description = "获取指定任务的分析结果")
    public Result<AnalysisResultVO> getResultByTask(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        AnalysisResultVO result = analysisResultService.getResultByTaskId(taskId, userDetails.getUserId());
        
        if (result == null) {
            return Result.success("该任务暂无分析结果", null);
        }
        
        return Result.success(result);
    }
    
    /**
     * 根据视频ID获取最新分析结果
     */
    @GetMapping("/video/{videoId}")
    @Operation(summary = "根据视频ID获取最新分析结果", description = "获取指定视频的最新分析结果")
    public Result<AnalysisResultVO> getResultByVideo(
            @Parameter(description = "视频ID") @PathVariable String videoId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        AnalysisResultVO result = analysisResultService.getLatestResultByVideoId(videoId, userDetails.getUserId());
        
        if (result == null) {
            return Result.success("该视频暂无分析结果", null);
        }
        
        return Result.success(result);
    }
    
    /**
     * 获取我的分析结果列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取我的分析结果列表", description = "分页获取当前用户的分析结果列表")
    public Result<Page<AnalysisResultVO>> getMyResults(
            @Parameter(description = "风险等级筛选（可选）: LOW/MEDIUM/HIGH") 
            @RequestParam(required = false) String riskLevel,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        Page<AnalysisResultVO> result = analysisResultService.getUserResults(
                userDetails.getUserId(), riskLevel, page, size);
        
        return Result.success(result);
    }
    
    /**
     * 获取分析统计数据
     */
    @GetMapping("/stats")
    @Operation(summary = "获取分析统计数据", description = "获取当前用户的分析统计数据")
    public Result<Map<String, Object>> getStats(
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        Map<String, Object> stats = analysisResultService.getUserAnalysisStats(userDetails.getUserId());
        
        return Result.success(stats);
    }
    
    /**
     * 获取风险分布统计
     */
    @GetMapping("/risk-distribution")
    @Operation(summary = "获取风险分布统计", description = "获取当前用户视频的风险等级分布")
    public Result<Map<String, Long>> getRiskDistribution(
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        Map<String, Long> distribution = analysisResultService.getRiskDistribution(userDetails.getUserId());
        
        return Result.success(distribution);
    }
    
    /**
     * 删除分析结果
     */
    @DeleteMapping("/{resultId}")
    @Operation(summary = "删除分析结果", description = "删除指定的分析结果")
    public Result<Void> deleteResult(
            @Parameter(description = "结果ID") @PathVariable String resultId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("删除分析结果: resultId={}", resultId);
        
        analysisResultService.deleteResult(resultId, userDetails.getUserId());
        
        return Result.success("删除成功", null);
    }
}

