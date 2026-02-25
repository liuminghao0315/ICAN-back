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
 * 风险词库包实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("word_pack")
public class WordPack {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 创建用户ID */
    private String userId;

    /** 词库包名称 */
    private String name;

    /** 词库包描述 */
    private String description;

    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
