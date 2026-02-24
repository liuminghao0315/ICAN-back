package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件夹实体类（CMS内容管理）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("folder")
public class Folder {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属用户ID */
    private String userId;

    /** 父文件夹ID（NULL表示根级） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String parentId;

    /** 文件夹名称 */
    private String name;

    /** 排序序号 */
    private Integer sortOrder;

    /** 是否系统预设目录 */
    private Boolean isSystem;

    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
