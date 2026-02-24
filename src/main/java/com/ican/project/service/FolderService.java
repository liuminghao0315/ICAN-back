package com.ican.project.service;

import com.ican.project.model.vo.FolderVO;

import java.util.List;

/**
 * 文件夹服务接口
 */
public interface FolderService {

    /**
     * 获取用户的文件夹树（含"全部记录"和"未分类"虚拟节点 + 自定义文件夹树）
     */
    List<FolderVO> getFolderTree(String userId);

    /**
     * 创建文件夹
     * @return 新建文件夹的VO
     */
    FolderVO createFolder(String userId, String parentId, String name);

    /**
     * 重命名文件夹
     */
    void renameFolder(String userId, String folderId, String newName);

    /**
     * 移动文件夹到新的父级，并指定在同级中的排序位置
     * @param newParentId 新父级ID（null=根级）
     * @param sortOrder   在新父级下的排序序号
     */
    void moveFolder(String userId, String folderId, String newParentId, int sortOrder);

    /**
     * 删除文件夹，视频一律移至未分类
     * @return 被移动到未分类的视频数量
     */
    int deleteFolder(String userId, String folderId);

    /**
     * 将视频移动到指定文件夹
     * @param folderId 目标文件夹ID，null表示移到未分类
     */
    void moveVideosToFolder(String userId, List<String> videoIds, String folderId);

    /**
     * 获取文件夹及其所有子文件夹的ID列表（递归）
     */
    List<String> getAllDescendantFolderIds(String userId, String folderId);
}
