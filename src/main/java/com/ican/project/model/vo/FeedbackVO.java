package com.ican.project.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackVO {
    private String id;
    private String userId;
    private String username;
    private String taskId;
    private String videoId;
    private String videoTitle;
    private String feedbackType;
    private String module;
    private String content;
    private String status;
    private String handlerId;
    private String handlerName;
    private String adminReply;
    private Object analysisSnapshot;
    private boolean videoDeleted;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
