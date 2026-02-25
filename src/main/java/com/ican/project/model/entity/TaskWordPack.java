package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务-词库包关联实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("task_word_pack")
public class TaskWordPack {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 分析任务ID */
    private String taskId;

    /** 词库包ID */
    private String packId;

    private LocalDateTime gmtCreated;
}
