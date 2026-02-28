package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("feedback_admin_unread")
public class FeedbackAdminUnread {
    @TableId
    private String feedbackId;
    private String adminId;
    private Integer unreadCount;
    private LocalDateTime gmtModified;
}
