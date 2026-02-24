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

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分析任务控制器
 */
@RestController
@RequestMapping("/api/analysis/task")
@Tag(name = "分析任务管理", description = "视频分析任务创建、查询、取消等接口")
public class AnalysisTaskController {
    
    private static final Logger logger = LoggerFactory.getLogger(AnalysisTaskController.class);

    /** 抖音每用户每日导入上限 */
    private static final int DOUYIN_DAILY_LIMIT = 20;

    /**
     * 抖音导入计数器：key = userId + ":" + date，value = 当日导入次数
     * 内存计数，重启后清零（对于限流场景足够）
     */
    private final ConcurrentHashMap<String, AtomicInteger> douyinImportCounter = new ConcurrentHashMap<>();

    /** 判断是否为抖音链接 */
    private boolean isDouyinUrl(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URI(url).getHost();
            return host != null && host.contains("douyin.com");
        } catch (Exception e) { return false; }
    }

    /** 检查并递增抖音导入计数，超限时返回 -1，否则返回当日已用次数 */
    private int checkAndIncrDouyinCount(String userId) {
        String key = userId + ":" + LocalDate.now();
        AtomicInteger counter = douyinImportCounter.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = counter.get();
        if (current >= DOUYIN_DAILY_LIMIT) return -1;
        return counter.incrementAndGet();
    }
    
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
     * URL预校验：验证链接是否可被 yt-dlp 解析，同时返回视频标题和结构化错误类型
     * 前端在弹窗内调用，校验失败直接拦截，不创建任何数据库记录
     */
    @PostMapping("/url-validate")
    @Operation(summary = "URL预校验", description = "验证视频链接是否可解析，返回视频标题和错误类型")
    public Result<java.util.Map<String, String>> validateUrl(
            @RequestBody java.util.Map<String, String> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {

        String url = body.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            java.util.Map<String, String> errData = new java.util.HashMap<>();
            errData.put("errorType", "INVALID_URL");
            return Result.fail(400, "URL不能为空");
        }

        logger.info("URL预校验: url={}", url);
        VideoDownloadService.UrlValidateResult result = videoDownloadService.validateUrlStructured(url);

        if (result.valid()) {
            java.util.Map<String, String> data = new java.util.HashMap<>();
            data.put("title", result.title());
            return Result.success("链接有效", data);
        }

        // 失败：把 errorType 放进 data 字段，前端可读取
        java.util.Map<String, String> errData = new java.util.HashMap<>();
        errData.put("errorType", result.errorType());
        errData.put("errorMessage", result.errorMessage());
        // 用 Result 的 data 字段携带错误类型，code=422
        return new Result<>(422, result.errorMessage(), errData);
    }

    /**
     * 保存 Cookies（供前端配置抖音等需要登录的平台）
     */
    @PostMapping("/save-cookies")
    @Operation(summary = "保存 Cookies", description = "将用户粘贴的 Netscape 格式 Cookies 写入服务器 cookies.txt")
    public Result<Void> saveCookies(
            @RequestBody java.util.Map<String, String> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        String content = body.getOrDefault("cookies", "").trim();
        if (content.isEmpty()) {
            return Result.fail(400, "Cookies 内容不能为空");
        }
        try {
            videoDownloadService.saveCookies(content);
            return Result.success("Cookies 保存成功", null);
        } catch (Exception e) {
            logger.error("保存 Cookies 失败: {}", e.getMessage());
            return Result.fail(500, "保存失败: " + e.getMessage());
        }
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
        String url = trimmed.getUrl();
        String userId = userDetails.getUserId();

        // ── 抖音每日导入限流 ──────────────────────────────────────
        if (isDouyinUrl(url)) {
            int used = checkAndIncrDouyinCount(userId);
            if (used == -1) {
                return Result.fail(429, "抖音视频每日最多导入 " + DOUYIN_DAILY_LIMIT + " 条，今日已达上限，明日再试");
            }
            logger.info("抖音导入计数: userId={}, 今日已用={}/{}", userId, used, DOUYIN_DAILY_LIMIT);
        }

        AnalysisTaskVO result = analysisTaskService.createUrlImportTask(
                url, trimmed.getTitle(), trimmed.getTaskType(), userId);

        // 异步启动视频下载（标题已由前端校验阶段获取并传入，跳过重复 fetchVideoTitle）
        videoDownloadService.downloadVideoAsync(url, result.getVideoId(), result.getId(), userId);

        return Result.success("任务创建成功，视频正在下载中", result);
    }
}

