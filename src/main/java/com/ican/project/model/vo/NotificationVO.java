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
public class NotificationVO {
    private String id;
    private String type;
    private String title;
    private String content;
    private String relatedId;
    private Boolean isRead;
    private LocalDateTime gmtCreated;
}
