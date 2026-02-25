package com.ican.project.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建/更新词库包请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WordPackDTO {

    /** 词库包名称 */
    private String name;

    /** 词库包描述 */
    private String description;

    /** 词汇列表（创建时可选，批量添加） */
    private List<WordItem> words;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WordItem {
        private String text;
        private String risk;
    }
}
