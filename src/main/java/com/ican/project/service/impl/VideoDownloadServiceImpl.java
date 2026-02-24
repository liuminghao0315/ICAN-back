package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.MinioService;
import com.ican.project.service.VideoDownloadService;
import com.ican.project.service.VideoService;
import com.ican.project.websocket.TaskProgressWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/**
 * URL视频下载服务实现
 * 集成 yt-dlp 进行外部视频资源下载
 * 下载完成后自动触发分析流水线
 */
@Service
public class VideoDownloadServiceImpl implements VideoDownloadService {
    
    private static final Logger logger = LoggerFactory.getLogger(VideoDownloadServiceImpl.class);
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private VideoService videoService;
    
    @Autowired
    private MinioService minioService;
    
    @Autowired
    private AnalysisTaskService analysisTaskService;
    
    @Value("${download.temp-dir:${java.io.tmpdir}/ican-downloads}")
    private String downloadTempDir;
    
    @Value("${download.ytdlp-path:yt-dlp}")
    private String ytdlpPath;
    
    @Override
    @Async("taskExecutor")
    public void downloadVideoAsync(String url, String videoId, String taskId, String userId) {
        Path tempDir = null;
        try {
            // 创建临时下载目录
            tempDir = Paths.get(downloadTempDir, videoId);
            Files.createDirectories(tempDir);
            
            String outputTemplate = tempDir.resolve("%(title)s.%(ext)s").toString();
            
            // 构建 yt-dlp 命令
            ProcessBuilder pb = new ProcessBuilder(
                ytdlpPath,
                "--no-playlist",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
                "-o", outputTemplate,
                "--no-overwrites",
                "--restrict-filenames",
                url
            );
            pb.redirectErrorStream(true);
            
            logger.info("开始下载视频: url={}, videoId={}", url, videoId);
            
            Process process = pb.start();
            
            // 读取输出日志
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[yt-dlp] {}", line);
                    // 可以解析进度信息推送 WebSocket
                    if (line.contains("[download]") && line.contains("%")) {
                        // 解析下载进度
                        try {
                            String progressStr = line.replaceAll(".*?(\\d+\\.?\\d*)%.*", "$1");
                            double progress = Double.parseDouble(progressStr);
                            // 通过 WebSocket 推送下载进度（映射到 0-10% 的任务进度）
                            int taskProgress = (int) (progress * 0.1);
                            analysisTaskService.updateTaskProgress(taskId, taskProgress, "下载中: " + (int) progress + "%");
                        } catch (Exception ignored) {}
                    }
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("yt-dlp 下载失败，退出码: " + exitCode);
            }
            
            // 查找下载的文件
            File downloadedFile = findDownloadedFile(tempDir.toFile());
            if (downloadedFile == null) {
                throw new RuntimeException("下载完成但未找到视频文件");
            }
            
            logger.info("视频下载完成: file={}, size={}", downloadedFile.getName(), downloadedFile.length());
            
            // 上传到 MinIO
            String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String extension = getFileExtension(downloadedFile.getName());
            String objectName = "videos/" + datePath + "/" + IdUtil.fastSimpleUUID() + "." + extension;
            
            try (InputStream is = new FileInputStream(downloadedFile)) {
                minioService.uploadFile(objectName, is, getContentType(extension), downloadedFile.length());
            }
            
            // 更新视频记录
            Video video = videoMapper.selectById(videoId);
            if (video != null) {
                video.setFileName(downloadedFile.getName());
                video.setFilePath(objectName);
                video.setFileSize(downloadedFile.length());
                video.setFileType(getContentType(extension));
                video.setStatus(Video.Status.UPLOADED.name());
                video.setGmtModified(LocalDateTime.now());
                videoMapper.updateById(video);
            }
            
            // 自动触发分析流水线：将任务状态从 DOWNLOADING 转为 PENDING
            analysisTaskService.markTaskPending(taskId);
            
            logger.info("视频下载并上传完成，已自动触发分析: videoId={}, taskId={}", videoId, taskId);
            
        } catch (Exception e) {
            logger.error("视频下载失败: url={}, videoId={}, error={}", url, videoId, e.getMessage(), e);
            
            // 标记任务失败
            try {
                analysisTaskService.markTaskFailed(taskId, "视频下载失败: " + e.getMessage());
                videoService.updateVideoStatus(videoId, Video.Status.FAILED.name());
            } catch (Exception ex) {
                logger.error("更新失败状态异常", ex);
            }
        } finally {
            // 清理临时文件
            if (tempDir != null) {
                cleanupTempFiles(tempDir);
            }
        }
    }
    
    private File findDownloadedFile(File dir) {
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov");
        });
        return (files != null && files.length > 0) ? files[0] : null;
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "mp4";
    }
    
    private String getContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "mp4" -> "video/mp4";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            default -> "application/octet-stream";
        };
    }
    
    private void cleanupTempFiles(Path dirPath) {
        try {
            if (Files.exists(dirPath)) {
                Files.walk(dirPath)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.delete(path); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException e) {
            logger.warn("清理下载临时目录失败: {}", dirPath);
        }
    }
}
