package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.vo.FolderVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.FolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文件夹管理控制器（CMS内容管理）
 */
@RestController
@RequestMapping("/api/folder")
@Tag(name = "文件夹管理", description = "文件夹CRUD、视频归档等接口")
public class FolderController {

    private static final Logger logger = LoggerFactory.getLogger(FolderController.class);

    @Autowired
    private FolderService folderService;

    /**
     * 获取文件夹树
     */
    @GetMapping("/tree")
    @Operation(summary = "获取文件夹树", description = "返回含虚拟节点（全部记录、未分类）和用户自定义文件夹树")
    public Result<List<FolderVO>> getFolderTree(@AuthenticationPrincipal MyUserDetails userDetails) {
        List<FolderVO> tree = folderService.getFolderTree(userDetails.getUserId());
        return Result.success(tree);
    }

    /**
     * 创建文件夹
     */
    @PostMapping
    @Operation(summary = "创建文件夹")
    public Result<FolderVO> createFolder(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        String name = body.get("name");
        String parentId = body.get("parentId");
        if (name == null || name.trim().isEmpty()) {
            return Result.fail(Code.PARAMETER_ERROR, "文件夹名称不能为空");
        }
        if (name.trim().length() > 100) {
            return Result.fail(Code.PARAMETER_ERROR, "文件夹名称不能超过100个字符");
        }
        try {
            FolderVO vo = folderService.createFolder(userDetails.getUserId(), parentId, name);
            return Result.success(vo);
        } catch (RuntimeException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /**
     * 重命名文件夹
     */
    @PutMapping("/{folderId}/rename")
    @Operation(summary = "重命名文件夹")
    public Result<Void> renameFolder(
            @PathVariable String folderId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        String newName = body.get("name");
        if (newName == null || newName.trim().isEmpty()) {
            return Result.fail(Code.PARAMETER_ERROR, "文件夹名称不能为空");
        }
        try {
            folderService.renameFolder(userDetails.getUserId(), folderId, newName);
            return Result.success(null);
        } catch (RuntimeException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /**
     * 移动文件夹
     */
    @PutMapping("/{folderId}/move")
    @Operation(summary = "移动文件夹（支持拖拽排序）")
    public Result<Void> moveFolder(
            @PathVariable String folderId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        String newParentId = (String) body.get("parentId");
        int sortOrder = body.get("sortOrder") != null
                ? ((Number) body.get("sortOrder")).intValue() : 0;
        try {
            folderService.moveFolder(userDetails.getUserId(), folderId, newParentId, sortOrder);
            return Result.success(null);
        } catch (RuntimeException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /**
     * 删除文件夹
     */
    @DeleteMapping("/{folderId}")
    @Operation(summary = "删除文件夹", description = "删除文件夹，其中的视频自动移至未分类，返回被移动的视频数量")
    public Result<Integer> deleteFolder(
            @PathVariable String folderId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            int movedCount = folderService.deleteFolder(userDetails.getUserId(), folderId);
            return Result.success(movedCount);
        } catch (RuntimeException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /**
     * 将视频移动到文件夹
     */
    @PutMapping("/move-videos")
    @Operation(summary = "将视频移动到指定文件夹")
    public Result<Void> moveVideosToFolder(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        @SuppressWarnings("unchecked")
        List<String> videoIds = (List<String>) body.get("videoIds");
        String folderId = (String) body.get("folderId"); // null 表示移到未分类
        if (videoIds == null || videoIds.isEmpty()) {
            return Result.fail(Code.PARAMETER_ERROR, "请选择要移动的视频");
        }
        try {
            folderService.moveVideosToFolder(userDetails.getUserId(), videoIds, folderId);
            return Result.success(null);
        } catch (RuntimeException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }
}
