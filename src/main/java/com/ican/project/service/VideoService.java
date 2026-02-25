package com.ican.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.dto.ChunkUploadDTO;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.ChunkUploadVO;
import com.ican.project.model.vo.VideoVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 视频服务接口
 */
public interface VideoService {
    
    /**
     * 初始化上传：在分片传输前先在数据库创建 UPLOADING 状态记录
     * @param fileName 文件名
     * @param title 视频标题
     * @param fileSize 文件大小（字节）
     * @param userId 用户ID
     * @return 视频信息（含 videoId）
     */
    VideoVO initUpload(String fileName, String title, long fileSize, String userId, String folderId);
    
    /**
     * 取消上传：物理中断后清理数据库记录和临时分片文件
     * @param videoId 视频ID
     * @param fileIdentifier 文件标识（用于清理临时文件）
     * @param userId 用户ID
     */
    void cancelUpload(String videoId, String fileIdentifier, String userId);
    
    /**
     * 检查分片上传状态（秒传检测）
     * @param fileIdentifier 文件标识
     * @param fileName 文件名
     * @param totalChunks 总分片数
     * @param userId 用户ID
     * @return 上传状态
     */
    ChunkUploadVO checkChunkUpload(String fileIdentifier, String fileName, 
                                   Integer totalChunks, String userId);
    
    /**
     * 上传分片
     * @param dto 分片信息
     * @param chunk 分片文件
     * @param userId 用户ID
     * @return 上传结果
     */
    ChunkUploadVO uploadChunk(ChunkUploadDTO dto, MultipartFile chunk, String userId);
    
    /**
     * 合并分片
     * @param fileIdentifier 文件标识
     * @param fileName 文件名
     * @param title 视频标题
     * @param description 视频描述
     * @param userId 用户ID
     * @return 视频信息，若合并期间任务已被取消则返回 null
     */
    VideoVO mergeChunks(String fileIdentifier, String fileName, 
                        String title, String description, String userId, String videoId);
    
    /**
     * 简单文件上传（小文件）
     * @param file 文件
     * @param title 标题
     * @param description 描述
     * @param userId 用户ID
     * @return 视频信息
     */
    VideoVO uploadSimple(MultipartFile file, String title, String description, String userId);
    
    /**
     * 简单文件上传（小文件），复用已有的 videoId
     * @param file 文件
     * @param title 标题
     * @param description 描述
     * @param userId 用户ID
     * @param videoId 已有的视频ID（initUpload 创建的），可为 null
     * @return 视频信息，若上传期间任务已被取消则返回 null
     */
    VideoVO uploadSimple(MultipartFile file, String title, String description, String userId, String videoId);
    
    /**
     * 获取视频详情
     * @param videoId 视频ID
     * @param userId 用户ID
     * @return 视频信息
     */
    VideoVO getVideoById(String videoId, String userId);
    
    /**
     * 获取用户视频列表
     * @param userId 用户ID
     * @param page 页码
     * @param size 每页数量
     * @param status 状态筛选（可选）
     * @param sortBy 排序字段（gmtCreated, fileSize, title）
     * @param sortOrder 排序方向（asc, desc）
     * @return 视频列表
     */
    Page<VideoVO> getUserVideos(String userId, int page, int size, String status, String sortBy, String sortOrder);
    
    /**
     * 删除视频
     * @param videoId 视频ID
     * @param userId 用户ID
     */
    void deleteVideo(String videoId, String userId);
    
    /**
     * 更新视频状态
     * @param videoId 视频ID
     * @param status 状态
     */
    void updateVideoStatus(String videoId, String status);
    
    /**
     * 重命名视频
     * @param videoId 视频ID
     * @param title 新标题
     * @param userId 用户ID
     */
    void renameVideo(String videoId, String title, String userId);

    /**
     * 生成缩略图并上传到 MinIO（供外部服务调用）
     * @param videoFilePath 本地视频文件路径
     * @param thumbObjectName MinIO 缩略图完整路径，如 "thumbnails/2024/01/01/<uuid>.jpg"
     * @return MinIO 缩略图 objectName，失败返回 null
     */
    String generateThumbnailAndUpload(java.nio.file.Path videoFilePath, String thumbObjectName);
}

