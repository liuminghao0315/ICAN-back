package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.exception.BusinessException;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.vo.AnalysisTaskVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 用户收藏控制器
 */
@RestController
@RequestMapping("/api/favorite")
@Tag(name = "用户收藏", description = "收藏/取消收藏分析任务，查询收藏列表")
public class FavoriteController {

    @Autowired
    private FavoriteService favoriteService;

    /**
     * 收藏分析任务
     */
    @PostMapping("/{taskId}")
    @Operation(summary = "收藏分析任务", description = "仅允许收藏已完成（COMPLETED）状态的任务")
    public Result<Void> addFavorite(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            favoriteService.addFavorite(userDetails.getUserId(), taskId);
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /**
     * 取消收藏
     */
    @DeleteMapping("/{taskId}")
    @Operation(summary = "取消收藏")
    public Result<Void> removeFavorite(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        favoriteService.removeFavorite(userDetails.getUserId(), taskId);
        return Result.success(null);
    }

    /**
     * 获取收藏列表（分页，默认按收藏时间倒序）
     */
    @GetMapping("/list")
    @Operation(summary = "获取收藏列表", description = "分页查询当前用户的收藏，默认按收藏时间倒序")
    public Result<Page<AnalysisTaskVO>> getFavoriteList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "12") int size,
            @Parameter(description = "风险等级筛选（可选）: LOW/MEDIUM/HIGH")
            @RequestParam(required = false) String riskLevel,
            @Parameter(description = "关键词搜索（可选）")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "来源类型筛选（可选）: LOCAL_UPLOAD/URL_IMPORT")
            @RequestParam(required = false) String sourceType,
            @Parameter(description = "排序字段: gmtCreated（收藏时间）/ riskScore")
            @RequestParam(defaultValue = "gmtCreated") String sortField,
            @Parameter(description = "排序方向: asc / desc")
            @RequestParam(defaultValue = "desc") String sortDir,
            @AuthenticationPrincipal MyUserDetails userDetails) {

        Page<AnalysisTaskVO> result = favoriteService.getFavoriteList(
                userDetails.getUserId(), page, size, riskLevel, keyword, sourceType, sortField, sortDir);
        return Result.success(result);
    }
}
