package com.ican.project.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文件夹视图对象（含子文件夹树和视频计数）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderVO {

    private String id;
    private String parentId;
    private String name;
    private Integer sortOrder;
    private Boolean isSystem;
    private String gmtCreated;

    /** 该文件夹（含子目录）下的视频总数 */
    private Integer videoCount;

    /** 子文件夹列表 */
    private List<FolderVO> children;
}
