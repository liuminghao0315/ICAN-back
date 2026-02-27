package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.common.Result;
import com.ican.project.model.vo.NotificationVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notification")
@Tag(name = "站内通知", description = "通知列表、未读数、标记已读")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @GetMapping("/list")
    @Operation(summary = "获取通知列表")
    public Result<Page<NotificationVO>> getList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        return Result.success(notificationService.getNotificationList(userDetails.getUserId(), page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "获取未读通知数量")
    public Result<Integer> getUnreadCount(@AuthenticationPrincipal MyUserDetails userDetails) {
        return Result.success(notificationService.getUnreadCount(userDetails.getUserId()));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "标记单条已读")
    public Result<Void> markAsRead(
            @PathVariable String id,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        notificationService.markAsRead(id, userDetails.getUserId());
        return Result.success(null);
    }

    @PutMapping("/read-all")
    @Operation(summary = "全部标记已读")
    public Result<Void> markAllAsRead(@AuthenticationPrincipal MyUserDetails userDetails) {
        notificationService.markAllAsRead(userDetails.getUserId());
        return Result.success(null);
    }
}
