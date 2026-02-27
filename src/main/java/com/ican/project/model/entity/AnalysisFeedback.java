package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("analysis_feedback")
public class AnalysisFeedback {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String taskId;
    private String videoId;
    private String feedbackType;
    private String module;
    private String content;
    private String videoTitle;
    private String analysisSnapshot;
    private String status;
    private String handlerId;
    private String adminReply;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
