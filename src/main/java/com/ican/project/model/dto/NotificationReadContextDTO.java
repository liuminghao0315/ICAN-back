package com.ican.project.model.dto;

import lombok.Data;

@Data
public class NotificationReadContextDTO {
    private String relatedType;
    private String feedbackId;
    private String videoId;
    private String targetPath;
}
