package com.ican.project.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 词库包视图对象（含词汇列表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WordPackVO {

    private String id;
    private String name;
    private String description;
    private Integer wordCount;

    /** 词汇列表 */
    private List<WordItem> words;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WordItem {
        private String id;
        private String text;
        private String risk;
    }
}
