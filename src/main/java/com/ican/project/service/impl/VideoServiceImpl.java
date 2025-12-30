package com.ican.project.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.UploadChunkMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.dto.ChunkUploadDTO;
import com.ican.project.model.entity.UploadChunk;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.ChunkUploadVO;
import com.ican.project.model.vo.VideoVO;
import com.ican.project.kafka.service.KafkaVideoAnalysisService;
import com.ican.project.service.MinioService;
import com.ican.project.service.UploadStatisticsService;
import com.ican.project.service.VideoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 视频服务实现
 */
@Service
public class VideoServiceImpl implements VideoService {
    
    private static final Logger logger = LoggerFactory.getLogger(VideoServiceImpl.class);
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private UploadChunkMapper uploadChunkMapper;
    
    @Autowired
    private MinioService minioService;
    
    @Autowired
    private UploadStatisticsService uploadStatisticsService;
    
    @Autowired(required = false)
    @Lazy
    private KafkaVideoAnalysisService kafkaVideoAnalysisService;
    
    @Value("${upload.temp-dir:${java.io.tmpdir}/ican-upload-chunks}")
    private String tempDir;
    
    /**
     * 初始化临时目录
     */
    @PostConstruct
    public void initTempDir() {
        try {
            Path tempDirPath = Paths.get(tempDir);
            if (!Files.exists(tempDirPath)) {
                Files.createDirectories(tempDirPath);
                logger.info("创建临时上传目录: {}", tempDirPath.toAbsolutePath());
            } else {
                logger.info("临时上传目录已存在: {}", tempDirPath.toAbsolutePath());
            }
        } catch (Exception e) {
            logger.error("初始化临时目录失败: {}", e.getMessage(), e);
            throw new RuntimeException("初始化临时目录失败", e);
        }
    }
    
    // 允许的视频文件扩展名
    private static final List<String> ALLOWED_EXTENSIONS = List.of(
            "mp4", "avi", "mov", "wmv", "flv", "mkv", "webm", "m4v", "mpeg", "mpg"
    );
    
    @Override
    public ChunkUploadVO checkChunkUpload(String fileIdentifier, String fileName, 
                                          Integer totalChunks, String userId) {
        // 验证文件扩展名
        validateFileExtension(fileName);
        
        // 获取已上传的分片列表
        List<Integer> uploadedChunks = uploadChunkMapper.getUploadedChunkNumbers(fileIdentifier);
        
        // 检查是否全部上传完成
        if (uploadedChunks.size() == totalChunks) {
            // 检查视频是否已存在
            LambdaQueryWrapper<Video> videoWrapper = new LambdaQueryWrapper<>();
            videoWrapper.eq(Video::getFileName, fileName)
                    .eq(Video::getUserId, userId)
                    .orderByDesc(Video::getGmtCreated)
                    .last("LIMIT 1");
            Video existingVideo = videoMapper.selectOne(videoWrapper);
            
            if (existingVideo != null) {
                return ChunkUploadVO.builder()
                        .needUpload(false)
                        .finished(true)
                        .videoId(existingVideo.getId())
                        .videoUrl(minioService.getFileUrl(existingVideo.getFilePath()))
                        .message("文件已存在，秒传成功")
                        .build();
            }
        }
        
        return ChunkUploadVO.builder()
                .needUpload(true)
                .uploadedChunks(uploadedChunks)
                .finished(false)
                .message("请继续上传未完成的分片")
                .build();
    }
    
    @Override
    @Transactional
    public ChunkUploadVO uploadChunk(ChunkUploadDTO dto, MultipartFile chunk, String userId) {
        // 验证文件扩展名
        validateFileExtension(dto.getFileName());
        
        String fileIdentifier = dto.getFileIdentifier();
        int chunkNumber = dto.getChunkNumber();
        
        try {
            // 创建临时目录（使用Path处理跨平台路径）
            Path chunkDirPath = Paths.get(tempDir, fileIdentifier);
            // 确保父目录存在
            Files.createDirectories(chunkDirPath);
            
            // 保存分片文件
            String chunkFileName = String.format("chunk_%05d", chunkNumber);
            Path chunkPath = chunkDirPath.resolve(chunkFileName);
            
            // 确保文件可以写入
            File chunkFile = chunkPath.toFile();
            if (!chunkFile.getParentFile().exists()) {
                chunkFile.getParentFile().mkdirs();
            }
            
            chunk.transferTo(chunkFile);
            
            // 记录分片信息
            LambdaQueryWrapper<UploadChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UploadChunk::getFileIdentifier, fileIdentifier)
                    .eq(UploadChunk::getChunkNumber, chunkNumber);
            UploadChunk existingChunk = uploadChunkMapper.selectOne(wrapper);
            
            if (existingChunk == null) {
                UploadChunk uploadChunk = UploadChunk.builder()
                        .id(IdUtil.fastSimpleUUID())
                        .fileIdentifier(fileIdentifier)
                        .userId(userId)
                        .fileName(dto.getFileName())
                        .totalChunks(dto.getTotalChunks())
                        .chunkNumber(chunkNumber)
                        .chunkSize(dto.getChunkSize())
                        .totalSize(dto.getTotalSize())
                        .chunkPath(chunkPath.toString())
                        .isUploaded(true)
                        .gmtCreated(LocalDateTime.now())
                        .build();
                uploadChunkMapper.insert(uploadChunk);
            } else {
                existingChunk.setIsUploaded(true);
                existingChunk.setChunkPath(chunkPath.toString());
                uploadChunkMapper.updateById(existingChunk);
            }
            
            // 检查是否所有分片都已上传
            int uploadedCount = uploadChunkMapper.countUploadedChunks(fileIdentifier);
            boolean allUploaded = uploadedCount == dto.getTotalChunks();
            
            if (allUploaded) {
                // 自动合并分片
                VideoVO videoVO = mergeChunks(fileIdentifier, dto.getFileName(), 
                        dto.getTitle(), dto.getDescription(), userId);
                
                return ChunkUploadVO.builder()
                        .needUpload(false)
                        .finished(true)
                        .videoId(videoVO.getId())
                        .videoUrl(videoVO.getVideoUrl())
                        .message("所有分片上传完成，文件合并成功")
                        .build();
            }
            
            return ChunkUploadVO.builder()
                    .needUpload(true)
                    .finished(false)
                    .uploadedChunks(uploadChunkMapper.getUploadedChunkNumbers(fileIdentifier))
                    .message("分片 " + chunkNumber + " 上传成功")
                    .build();
            
        } catch (Exception e) {
            logger.error("分片上传失败: {}", e.getMessage(), e);
            throw new BusinessException("分片上传失败: " + e.getMessage());
        }
    }
    
    @Override
    @Transactional
    public VideoVO mergeChunks(String fileIdentifier, String fileName, 
                               String title, String description, String userId) {
        // 使用Path处理跨平台路径
        Path chunkDirPath = Paths.get(tempDir, fileIdentifier);
        
        try {
            // 获取所有分片信息
            LambdaQueryWrapper<UploadChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UploadChunk::getFileIdentifier, fileIdentifier)
                    .eq(UploadChunk::getIsUploaded, true)
                    .orderByAsc(UploadChunk::getChunkNumber);
            List<UploadChunk> chunks = uploadChunkMapper.selectList(wrapper);
            
            if (chunks.isEmpty()) {
                throw new BusinessException("没有找到已上传的分片");
            }
            
            // 获取文件信息
            UploadChunk firstChunk = chunks.get(0);
            long totalSize = firstChunk.getTotalSize();
            String fileExt = FileUtil.extName(fileName);
            String contentType = getContentType(fileExt);
            
            // 生成存储路径
            String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String objectName = "videos/" + datePath + "/" + IdUtil.fastSimpleUUID() + "." + fileExt;
            
            // 合并分片并上传到MinIO
            Path mergedFilePath = chunkDirPath.resolve("merged_" + fileName);
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(mergedFilePath.toFile()))) {
                for (UploadChunk chunk : chunks) {
                    Path chunkPath = Paths.get(chunk.getChunkPath());
                    if (Files.exists(chunkPath)) {
                        Files.copy(chunkPath, os);
                    }
                }
            }
            
            // 上传到MinIO
            try (InputStream is = new BufferedInputStream(new FileInputStream(mergedFilePath.toFile()))) {
                minioService.uploadFile(objectName, is, contentType, totalSize);
            }
            
            // 创建视频记录
            Video video = Video.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .userId(userId)
                    .title(title != null ? title : fileName)
                    .description(description)
                    .fileName(fileName)
                    .filePath(objectName)
                    .fileSize(totalSize)
                    .fileType(contentType)
                    .status(Video.Status.UPLOADED.name())
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now())
                    .build();
            videoMapper.insert(video);
            
            // 记录上传统计（持久化）
            try {
                uploadStatisticsService.recordUpload(userId, totalSize);
            } catch (Exception e) {
                logger.warn("记录上传统计失败: {}", e.getMessage());
            }
            
            // 清理临时文件
            cleanupTempFiles(chunkDirPath);
            
            // 删除分片记录
            LambdaQueryWrapper<UploadChunk> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(UploadChunk::getFileIdentifier, fileIdentifier);
            uploadChunkMapper.delete(deleteWrapper);
            
            logger.info("视频上传成功: videoId={}, fileName={}", video.getId(), fileName);
            
            // 注意：不自动触发分析，由用户手动点击"开始分析"按钮触发
            
            return convertToVO(video);
            
        } catch (Exception e) {
            logger.error("合并分片失败: {}", e.getMessage(), e);
            throw new BusinessException("合并分片失败: " + e.getMessage());
        }
    }
    
    @Override
    @Transactional
    public VideoVO uploadSimple(MultipartFile file, String title, String description, String userId) {
        String fileName = file.getOriginalFilename();
        
        // 验证文件扩展名
        validateFileExtension(fileName);
        
        try {
            String fileExt = FileUtil.extName(fileName);
            String contentType = file.getContentType();
            
            // 生成存储路径
            String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String objectName = "videos/" + datePath + "/" + IdUtil.fastSimpleUUID() + "." + fileExt;
            
            // 上传到MinIO
            minioService.uploadFile(objectName, file);
            
            // 创建视频记录
            Video video = Video.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .userId(userId)
                    .title(title != null ? title : fileName)
                    .description(description)
                    .fileName(fileName)
                    .filePath(objectName)
                    .fileSize(file.getSize())
                    .fileType(contentType)
                    .status(Video.Status.UPLOADED.name())
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now())
                    .build();
            videoMapper.insert(video);
            
            // 记录上传统计（持久化）
            try {
                uploadStatisticsService.recordUpload(userId, file.getSize());
            } catch (Exception e) {
                logger.warn("记录上传统计失败: {}", e.getMessage());
            }
            
            logger.info("视频上传成功: videoId={}, fileName={}", video.getId(), fileName);
            
            // 注意：不自动触发分析，由用户手动点击"开始分析"按钮触发
            
            return convertToVO(video);
            
        } catch (Exception e) {
            logger.error("视频上传失败: {}", e.getMessage(), e);
            throw new BusinessException("视频上传失败: " + e.getMessage());
        }
    }
    
    @Override
    public VideoVO getVideoById(String videoId, String userId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在");
        }
        if (!video.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该视频");
        }
        return convertToVO(video);
    }
    
    @Override
    public Page<VideoVO> getUserVideos(String userId, int page, int size, String status, String sortBy, String sortOrder) {
        Page<Video> videoPage = new Page<>(page, size);
        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Video::getUserId, userId);
        
        // 状态筛选
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Video::getStatus, status.toUpperCase());
        }
        
        // 动态排序
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        switch (sortBy != null ? sortBy : "gmtCreated") {
            case "fileSize":
                wrapper.orderBy(true, isAsc, Video::getFileSize);
                break;
            case "title":
                wrapper.orderBy(true, isAsc, Video::getTitle);
                break;
            case "gmtCreated":
            default:
                wrapper.orderBy(true, isAsc, Video::getGmtCreated);
                break;
        }
        
        Page<Video> result = videoMapper.selectPage(videoPage, wrapper);
        
        Page<VideoVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList()));
        
        return voPage;
    }
    
    @Override
    @Transactional
    public void deleteVideo(String videoId, String userId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在");
        }
        if (!video.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该视频");
        }
        
        // 删除MinIO文件
        try {
            minioService.deleteFile(video.getFilePath());
            if (video.getThumbnailPath() != null) {
                minioService.deleteFile(video.getThumbnailPath());
            }
        } catch (Exception e) {
            logger.warn("删除MinIO文件失败: {}", e.getMessage());
        }
        
        // 删除数据库记录
        videoMapper.deleteById(videoId);
        logger.info("视频删除成功: videoId={}", videoId);
    }
    
    @Override
    public void updateVideoStatus(String videoId, String status) {
        Video video = new Video();
        video.setId(videoId);
        video.setStatus(status);
        video.setGmtModified(LocalDateTime.now());
        videoMapper.updateById(video);
    }
    
    /**
     * 转换为VO
     */
    private VideoVO convertToVO(Video video) {
        return VideoVO.builder()
                .id(video.getId())
                .title(video.getTitle())
                .description(video.getDescription())
                .fileName(video.getFileName())
                .fileSize(video.getFileSize())
                .fileType(video.getFileType())
                .duration(video.getDuration())
                .width(video.getWidth())
                .height(video.getHeight())
                .thumbnailUrl(video.getThumbnailPath() != null ? 
                        minioService.getFileUrl(video.getThumbnailPath()) : null)
                .videoUrl(minioService.getFileUrl(video.getFilePath()))
                .status(video.getStatus())
                .gmtCreated(video.getGmtCreated())
                .build();
    }
    
    /**
     * 验证文件扩展名
     */
    private void validateFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        String extension = FileUtil.extName(fileName);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new BusinessException("不支持的视频格式，允许的格式: " + String.join(", ", ALLOWED_EXTENSIONS));
        }
    }
    
    /**
     * 获取内容类型
     */
    private String getContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "mp4" -> "video/mp4";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }
    
    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(Path dirPath) {
        try {
            if (Files.exists(dirPath)) {
                Files.walk(dirPath)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                logger.warn("删除临时文件失败: {}", path);
                            }
                        });
            }
        } catch (IOException e) {
            logger.warn("清理临时目录失败: {}", dirPath);
        }
    }
}

