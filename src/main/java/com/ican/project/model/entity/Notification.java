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
@TableName("notification")
public class Notification {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String type;
    private String title;
    private String content;
    private String relatedId;
    private Boolean isRead;
    private LocalDateTime gmtCreated;
}
