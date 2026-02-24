package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.ChunkUploadDTO;
import com.ican.project.model.dto.VideoRenameDTO;
import com.ican.project.model.vo.ChunkUploadVO;
import com.ican.project.model.vo.VideoVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频管理控制器
 */
@RestController
@RequestMapping("/api/video")
@Tag(name = "视频管理", description = "视频上传、查询、删除等接口")
public class VideoController {
    
    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);
    
    @Autowired
    private VideoService videoService;
    
    /**
     * 检查分片上传状态（用于断点续传和秒传）
     */
    @GetMapping("/check-chunk")
    @Operation(summary = "检查分片上传状态", description = "检查文件是否已上传或获取已上传的分片列表")
    public Result<ChunkUploadVO> checkChunkUpload(
            @Parameter(description = "文件唯一标识（MD5）") @RequestParam String fileIdentifier,
            @Parameter(description = "文件名") @RequestParam String fileName,
            @Parameter(description = "总分片数") @RequestParam Integer totalChunks,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("检查分片上传状态: fileIdentifier={}, fileName={}", fileIdentifier, fileName);
        
        ChunkUploadVO result = videoService.checkChunkUpload(
                fileIdentifier, fileName, totalChunks, userDetails.getUserId());
        
        return Result.success(result);
    }
    
    /**
     * 初始化上传（持久化先行）
     */
    @PostMapping("/init-upload")
    @Operation(summary = "初始化上传", description = "在分片传输前先在数据库创建 UPLOADING 状态记录")
    public Result<VideoVO> initUpload(
            @Parameter(description = "文件名") @RequestParam String fileName,
            @Parameter(description = "视频标题") @RequestParam(required = false) String title,
            @Parameter(description = "文件大小（字节）") @RequestParam long fileSize,
            @Parameter(description = "目标文件夹ID（可选）") @RequestParam(required = false) String folderId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("初始化上传: fileName={}, fileSize={}", fileName, fileSize);
        VideoVO result = videoService.initUpload(fileName, title, fileSize, userDetails.getUserId(), folderId);
        return Result.success("初始化成功", result);
    }
    
    /**
     * 取消上传（清理 DB 记录和临时分片）
     */
    @DeleteMapping("/{videoId}/cancel-upload")
    @Operation(summary = "取消上传", description = "物理中断后清理数据库记录和临时分片文件")
    public Result<Void> cancelUpload(
            @Parameter(description = "视频ID") @PathVariable String videoId,
            @Parameter(description = "文件标识（用于清理临时文件）") @RequestParam(required = false) String fileIdentifier,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("取消上传: videoId={}, fileIdentifier={}", videoId, fileIdentifier);
        videoService.cancelUpload(videoId, fileIdentifier, userDetails.getUserId());
        return Result.success("已取消", null);
    }
    
    /**
     * 上传分片
     */
    @PostMapping("/upload-chunk")
    @Operation(summary = "上传分片", description = "上传视频文件分片")
    public Result<ChunkUploadVO> uploadChunk(
            @Valid ChunkUploadDTO dto,
            @Parameter(description = "分片文件") @RequestParam("chunk") MultipartFile chunk,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("上传分片: fileIdentifier={}, chunkNumber={}/{}", 
                dto.getFileIdentifier(), dto.getChunkNumber(), dto.getTotalChunks());
        
        ChunkUploadVO result = videoService.uploadChunk(dto, chunk, userDetails.getUserId());
        
        return Result.success(result);
    }
    
    /**
     * 简单上传（小文件）
     */
    @PostMapping("/upload")
    @Operation(summary = "简单上传", description = "上传小视频文件（建议小于50MB）")
    public Result<VideoVO> uploadSimple(
            @Parameter(description = "视频文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "视频标题") @RequestParam(required = false) String title,
            @Parameter(description = "视频描述") @RequestParam(required = false) String description,
            @Parameter(description = "已有的视频ID（initUpload创建的）") @RequestParam(required = false) String videoId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("简单上传视频: fileName={}, size={}, videoId={}", file.getOriginalFilename(), file.getSize(), videoId);
        
        VideoVO result = videoService.uploadSimple(file, title, description, userDetails.getUserId(), videoId);
        
        // result 为 null 表示上传期间任务已被取消
        if (result == null) {
            return Result.success("上传已取消", null);
        }
        
        return Result.success("上传成功", result);
    }
    
    /**
     * 获取视频详情
     */
    @GetMapping("/{videoId}")
    @Operation(summary = "获取视频详情", description = "根据视频ID获取视频详细信息")
    public Result<VideoVO> getVideo(
            @Parameter(description = "视频ID") @PathVariable String videoId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        VideoVO result = videoService.getVideoById(videoId, userDetails.getUserId());
        
        return Result.success(result);
    }
    
    /**
     * 获取我的视频列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取我的视频列表", description = "分页获取当前用户的视频列表")
    public Result<Page<VideoVO>> getMyVideos(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "状态筛选") @RequestParam(required = false) String status,
            @Parameter(description = "排序字段: gmtCreated, fileSize, title") @RequestParam(defaultValue = "gmtCreated") String sortBy,
            @Parameter(description = "排序方向: asc, desc") @RequestParam(defaultValue = "desc") String sortOrder,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        Page<VideoVO> result = videoService.getUserVideos(userDetails.getUserId(), page, size, status, sortBy, sortOrder);
        
        return Result.success(result);
    }
    
    /**
     * 删除视频
     */
    @DeleteMapping("/{videoId}")
    @Operation(summary = "删除视频", description = "删除指定视频")
    public Result<Void> deleteVideo(
            @Parameter(description = "视频ID") @PathVariable String videoId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("删除视频: videoId={}", videoId);
        
        videoService.deleteVideo(videoId, userDetails.getUserId());
        
        return Result.success("删除成功", null);
    }
    
    /**
     * 重命名视频
     */
    @PutMapping("/{videoId}/rename")
    @Operation(summary = "重命名视频", description = "修改视频标题")
    public Result<Void> renameVideo(
            @Parameter(description = "视频ID") @PathVariable String videoId,
            @Valid @RequestBody VideoRenameDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("重命名视频: videoId={}, newTitle={}", videoId, dto.getTitle());
        
        videoService.renameVideo(videoId, dto.getTitle(), userDetails.getUserId());
        
        return Result.success("重命名成功", null);
    }
}

