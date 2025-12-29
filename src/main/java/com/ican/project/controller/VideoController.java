package com.ican.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.ChunkUploadDTO;
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
            @AuthenticationPrincipal MyUserDetails userDetails) {
        
        logger.info("简单上传视频: fileName={}, size={}", file.getOriginalFilename(), file.getSize());
        
        VideoVO result = videoService.uploadSimple(file, title, description, userDetails.getUserId());
        
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
}

