package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.FeedbackCreateDTO;
import com.ican.project.model.vo.FeedbackVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.AnalysisFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@Tag(name = "分析反馈", description = "用户提交分析结果反馈，管理员审核处理")
public class AnalysisFeedbackController {

    @Autowired
    private AnalysisFeedbackService feedbackService;

    @Autowired
    private UserMapper userMapper;

    @PostMapping
    @Operation(summary = "提交反馈")
    public Result<FeedbackVO> submitFeedback(
            @Valid @RequestBody FeedbackCreateDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        FeedbackVO vo = feedbackService.submitFeedback(userDetails.getUserId(), dto);
        return Result.success(vo);
    }

    @GetMapping("/my")
    @Operation(summary = "我的反馈历史")
    public Result<Page<FeedbackVO>> getMyFeedbacks(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        return Result.success(feedbackService.getMyFeedbacks(userDetails.getUserId(), page, size));
    }

    @GetMapping("/my/video/{videoId}")
    @Operation(summary = "查看我对某视频的反馈")
    public Result<FeedbackVO> getMyFeedbackByVideo(
            @PathVariable String videoId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        FeedbackVO vo = feedbackService.getMyFeedbackByVideo(userDetails.getUserId(), videoId);
        return Result.success(vo);
    }

    @GetMapping("/admin/list")
    @Operation(summary = "管理员查看反馈列表")
    public Result<?> getAdminFeedbackList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "状态筛选") @RequestParam(required = false) String status,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        if (!isAdmin(userDetails.getUserId())) {
            return Result.accessFail("仅管理员可访问");
        }
        return Result.success(feedbackService.getAdminFeedbackList(page, size, status));
    }

    @PutMapping("/{id}/lock")
    @Operation(summary = "管理员锁定反馈（开始处理）")
    public Result<Void> lockFeedback(
            @PathVariable String id,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        if (!isAdmin(userDetails.getUserId())) {
            return Result.accessFail("仅管理员可操作");
        }
        try {
            feedbackService.lockFeedback(id, userDetails.getUserId());
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{id}/reply")
    @Operation(summary = "管理员回复消息")
    public Result<Void> replyFeedback(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        if (!isAdmin(userDetails.getUserId())) {
            return Result.accessFail("仅管理员可操作");
        }
        String reply = body.get("reply");
        if (reply == null || reply.trim().isEmpty()) {
            return Result.fail(Code.PARAMETER_ERROR, "回复内容不能为空");
        }
        try {
            feedbackService.replyFeedback(id, userDetails.getUserId(), reply.trim());
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{id}/close")
    @Operation(summary = "管理员关闭反馈（标记已解决/已驳回）")
    public Result<Void> closeFeedback(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        if (!isAdmin(userDetails.getUserId())) {
            return Result.accessFail("仅管理员可操作");
        }
        String status = body.getOrDefault("status", "RESOLVED");
        try {
            feedbackService.closeFeedback(id, userDetails.getUserId(), status);
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    private boolean isAdmin(String userId) {
        List<String> roles = userMapper.selectRoleNamesByUserId(userId);
        return roles != null && roles.contains("Administrator");
    }
}
