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
 * 词库包词汇实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("word_pack_word")
public class WordPackWord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属词库包ID */
    private String packId;

    /** 词汇内容 */
    private String text;

    /** 风险等级: high/medium/low */
    private String risk;

    private LocalDateTime gmtCreated;
}
