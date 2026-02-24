package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.ican.project.mapper.VideoMapper;
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
import java.nio.charset.StandardCharsets;
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

            // ── Step 1: 获取视频真实标题 ──────────────────────────────
            String fetchedTitle = fetchVideoTitle(url);
            if (fetchedTitle == null) {
                logger.warn("获取视频标题失败");
            } else {
                logger.info("获取到视频标题: {}", fetchedTitle);
            }

            // 读取当前 Video 记录，判断用户是否填写了自定义标题
            Video video = videoMapper.selectById(videoId);
            // 判断是否为用户自定义标题：非空且不是系统自动生成的占位标题（以"链接导入 - "开头）
            boolean hasUserCustomTitle = video != null
                    && video.getTitle() != null
                    && !video.getTitle().isBlank()
                    && !video.getTitle().startsWith("链接导入 - ");

            String videoTitle;
            if (hasUserCustomTitle) {
                // 用户填了自定义标题，保留不覆盖
                videoTitle = video.getTitle();
                logger.info("用户已设置自定义标题「{}」，保留不覆盖", videoTitle);
            } else {
                // 无自定义标题，使用真实标题（或保底标题）
                if (fetchedTitle != null) {
                    videoTitle = fetchedTitle;
                } else {
                    videoTitle = "链接导入 - " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                    logger.warn("使用保底标题: {}", videoTitle);
                }
                // 将真实标题写入 Video 记录
                if (video != null) {
                    video.setTitle(videoTitle);
                    video.setGmtModified(LocalDateTime.now());
                    videoMapper.updateById(video);
                }
            }

            // 推送「元数据已获取」消息；若是用户自定义标题则不推送标题更新（避免覆盖前端已显示的标题）
            TaskProgressWebSocket.sendTaskProgress(
                userId, taskId, videoId,
                "DOWNLOADING", 0, "元数据已获取，准备下载",
                "FETCHING_TITLE", hasUserCustomTitle ? null : videoTitle
            );

            // ── Step 2: 构造安全文件名（去除 Windows 不允许的字符）────
            String safeTitle = sanitizeFileName(videoTitle);
            // 文件名过长时截断（Windows MAX_PATH 限制）
            if (safeTitle.length() > 80) {
                safeTitle = safeTitle.substring(0, 80).trim();
            }
            String outputTemplate = tempDir.resolve(safeTitle + ".%(ext)s").toString();

            // ── Step 3: 执行下载 ──────────────────────────────────────
            ProcessBuilder pb = new ProcessBuilder(
                ytdlpPath,
                "--no-playlist",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
                "-o", outputTemplate,
                "--no-overwrites",
                "--socket-timeout", "30",
                "--encoding", "utf-8",
                url
            );
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            logger.info("开始下载视频: url={}, videoId={}, ytdlpPath={}, tempDir={}", url, videoId, ytdlpPath, tempDir);

            Process process = pb.start();
            
            // 读取输出日志，同时收集错误信息（指定 UTF-8 编码，避免 Windows 下乱码）
            // yt-dlp 分两段下载：视频流(0→100%) + 音频流(0→100%)，需要分段映射到总进度
            // 映射规则：视频流 → 0~60%，音频流 → 60~90%，合并/上传 → 90~95%
            StringBuilder outputLog = new StringBuilder();
            int downloadPhase = 0; // 0=视频流, 1=音频流
            int lastSentProgress = -1; // 防止重复推送相同进度
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[yt-dlp] {}", line);
                    outputLog.append(line).append("\n");

                    // 检测是否进入第二段（音频流）下载
                    // yt-dlp 输出 "[download] Destination: xxx.m4a" 或进度从高位归零时切换
                    if (line.contains("[download] Destination:")) {
                        String dest = line.toLowerCase();
                        if (dest.endsWith(".m4a") || dest.endsWith(".opus") || dest.endsWith(".aac") || dest.endsWith(".webm.audio")) {
                            downloadPhase = 1;
                        }
                    }

                    if (line.contains("[download]") && line.contains("%")) {
                        try {
                            String progressStr = line.replaceAll(".*?(\\d+\\.?\\d*)%.*", "$1");
                            double rawProgress = Double.parseDouble(progressStr);

                            // 映射到总进度
                            int taskProgress;
                            String msg;
                            if (downloadPhase == 0) {
                                taskProgress = (int) (rawProgress * 0.6);   // 0→60
                                msg = "下载视频流: " + (int) rawProgress + "%";
                            } else {
                                taskProgress = 60 + (int) (rawProgress * 0.3); // 60→90
                                msg = "下载音频流: " + (int) rawProgress + "%";
                            }

                            // 只在进度前进时推送，避免同值重复推送
                            if (taskProgress > lastSentProgress) {
                                lastSentProgress = taskProgress;
                                TaskProgressWebSocket.sendTaskProgress(
                                    userId, taskId, videoId,
                                    "DOWNLOADING", taskProgress, msg,
                                    "DOWNLOADING", null
                                );
                            }
                        } catch (Exception ignored) {}
                    }

                    // 检测合并阶段
                    if (line.contains("[Merger]") || line.contains("[ffmpeg]") || line.contains("Merging")) {
                        if (lastSentProgress < 90) {
                            lastSentProgress = 90;
                            TaskProgressWebSocket.sendTaskProgress(
                                userId, taskId, videoId,
                                "DOWNLOADING", 90, "合并音视频流...",
                                "DOWNLOADING", null
                            );
                        }
                    }
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // 提取最后几行错误信息给用户看
                String[] lines = outputLog.toString().split("\n");
                String lastError = "";
                for (int i = lines.length - 1; i >= 0 && i >= lines.length - 5; i--) {
                    if (!lines[i].trim().isEmpty()) {
                        lastError = lines[i].trim();
                        break;
                    }
                }
                throw new RuntimeException("yt-dlp 下载失败 (退出码 " + exitCode + "): " + lastError);
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
            
            // 更新视频记录（title 已在下载前写入，此处只更新文件相关字段）
            video = videoMapper.selectById(videoId);
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
            // 先检查任务是否已被用户取消，避免覆盖取消状态
            String currentTaskStatus = analysisTaskService.getTaskStatus(taskId);
            if ("CANCELLED".equals(currentTaskStatus)) {
                logger.info("任务已被取消，跳过触发分析: taskId={}", taskId);
                return;
            }

            // 推送「下载完成，进入分析队列」，同时携带 videoUrl 供前端截帧生成缩略图
            String videoUrl = null;
            try {
                videoUrl = minioService.getFileUrl(objectName);
            } catch (Exception ignored) {}
            TaskProgressWebSocket.sendTaskProgress(
                userId, taskId, videoId,
                "PENDING", 95, "视频下载完成，等待 AI 分析",
                "PENDING", null, videoUrl
            );
            analysisTaskService.markTaskPending(taskId);
            
            logger.info("视频下载并上传完成，已自动触发分析: videoId={}, taskId={}", videoId, taskId);
            
        } catch (Exception e) {
            logger.error("视频下载失败: url={}, videoId={}, error={}", url, videoId, e.getMessage(), e);

            // 提取用户友好的错误信息
            String userMsg = extractUserFriendlyError(e.getMessage());

            // 通过 WebSocket 立即通知前端卡片切换为失败状态
            try {
                TaskProgressWebSocket.sendTaskFailed(userId, taskId, videoId, userMsg);
            } catch (Exception wsEx) {
                logger.warn("推送失败状态 WS 消息异常: {}", wsEx.getMessage());
            }

            // 标记任务失败
            try {
                analysisTaskService.markTaskFailed(taskId, userMsg);
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
        File[] allFiles = dir.listFiles();
        if (allFiles != null) {
            for (File f : allFiles) {
                logger.info("[yt-dlp] 临时目录文件: {} ({}字节)", f.getName(), f.length());
            }
        }
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov");
        });
        if (files != null && files.length > 0) {
            // 选最大的文件（避免选到空文件或临时文件）
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.length(), a.length()));
            return files[0];
        }
        return null;
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

    /**
     * 预校验 URL 是否可被 yt-dlp 解析（同步，用于提交前拦截）
     * 直接复用 fetchVideoTitle：能拿到标题 = 链接有效
     */
    @Override
    public String validateUrl(String url) {
        return fetchVideoTitle(url);
    }

    /**
     * 使用 yt-dlp --get-title 获取视频真实标题
     * 超时 15 秒，失败返回 null
     */
    private String fetchVideoTitle(String url) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ytdlpPath,
                "--get-title",
                "--no-playlist",
                "--socket-timeout", "15",
                "--encoding", "utf-8",
                url
            );
            pb.redirectErrorStream(false); // 分离 stderr，只读 stdout
            Process process = pb.start();

            // 异步消费 stderr，防止缓冲区阻塞
            Thread stderrDrain = new Thread(() -> {
                try (InputStream err = process.getErrorStream()) {
                    byte[] buf = new byte[1024];
                    while (err.read(buf) != -1) { /* discard */ }
                } catch (IOException ignored) {}
            });
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            String title = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    title = line.trim();
                }
            }

            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("yt-dlp --get-title 超时");
                return null;
            }
            return (process.exitValue() == 0) ? title : null;
        } catch (Exception e) {
            logger.warn("yt-dlp --get-title 执行异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将标题转为 Windows/Linux 均安全的文件名
     */
    private String sanitizeFileName(String title) {
        if (title == null || title.isBlank()) return "video";
        String safe = title
            .replaceAll("[\\\\/:*?\"<>|]", "")
            .replaceAll("[\\x00-\\x1F]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return safe.isEmpty() ? "video" : safe;
    }

    /**
     * 从异常信息中提取用户友好的错误描述
     */
    private String extractUserFriendlyError(String rawMsg) {
        if (rawMsg == null) return "下载失败，原因未知";
        if (rawMsg.contains("Unsupported URL")) return "不支持该平台或链接格式";
        if (rawMsg.contains("Video unavailable")) return "视频不可用（已删除或设为私密）";
        if (rawMsg.contains("Private video")) return "该视频为私密视频，无法下载";
        if (rawMsg.contains("Sign in")) return "该视频需要登录才能访问";
        if (rawMsg.contains("socket timeout") || rawMsg.contains("timed out")) return "网络超时，请稍后重试";
        if (rawMsg.contains("HTTP Error 403")) return "访问被拒绝（403），该内容可能有地区限制";
        if (rawMsg.contains("HTTP Error 404")) return "视频不存在（404）";
        if (rawMsg.contains("未找到视频文件")) return "下载完成但文件丢失，请重试";
        // 截取 yt-dlp 原始错误的最后一行，去掉冗余前缀
        String[] parts = rawMsg.split("\\): ", 2);
        return "下载失败: " + (parts.length > 1 ? parts[1] : rawMsg).trim();
    }
}
