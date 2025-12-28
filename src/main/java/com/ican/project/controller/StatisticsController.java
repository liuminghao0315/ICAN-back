package com.ican.project.controller;

import com.ican.project.model.common.Result;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.UploadStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 统计数据接口
 */
@Tag(name = "统计数据", description = "数据统计相关接口")
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {
    
    private final UploadStatisticsService uploadStatisticsService;
    
    /**
     * 获取上传趋势数据
     */
    @Operation(summary = "获取上传趋势", description = "获取最近N天的上传统计数据")
    @GetMapping("/upload-trend")
    public Result<List<Map<String, Object>>> getUploadTrend(
            @Parameter(description = "天数，默认7天") @RequestParam(defaultValue = "7") int days,
            @AuthenticationPrincipal MyUserDetails userDetails
    ) {
        String userId = userDetails.getUserId();
        List<Map<String, Object>> trend = uploadStatisticsService.getUploadTrend(userId, days);
        return Result.success(trend);
    }
    
    /**
     * 获取总体统计数据
     */
    @Operation(summary = "获取总体统计", description = "获取用户的总上传统计数据")
    @GetMapping("/total")
    public Result<Map<String, Object>> getTotalStats(
            @AuthenticationPrincipal MyUserDetails userDetails
    ) {
        String userId = userDetails.getUserId();
        Map<String, Object> stats = uploadStatisticsService.getTotalStats(userId);
        return Result.success(stats);
    }
}
