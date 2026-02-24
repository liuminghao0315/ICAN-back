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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Set;

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

    /** yt-dlp cookies 文件路径（可选，用于需要登录的平台如抖音） */
    @Value("${download.cookies-file:}")
    private String cookiesFile;

    /** 直接视频 URL 的扩展名集合（无需 yt-dlp，直接 HTTP 下载） */
    private static final Set<String> DIRECT_VIDEO_EXTENSIONS = Set.of(
        "mp4", "m4v", "flv", "mov", "avi", "mkv", "webm", "wmv", "ts", "m3u8"
    );

    /**
     * 判断 URL 是否为直接视频文件地址（以视频扩展名结尾，忽略查询参数）
     */
    private boolean isDirectVideoUrl(String url) {
        if (url == null) return false;
        try {
            String path = new URL(url).getPath().toLowerCase();
            int dot = path.lastIndexOf('.');
            if (dot < 0) return false;
            String ext = path.substring(dot + 1);
            return DIRECT_VIDEO_EXTENSIONS.contains(ext);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @Async("taskExecutor")
    public void downloadVideoAsync(String url, String videoId, String taskId, String userId) {
        Path tempDir = null;
        try {
            // 创建临时下载目录
            tempDir = Paths.get(downloadTempDir, videoId);
            Files.createDirectories(tempDir);

            // ── 路由判断：直接视频 URL vs 平台链接 ──────────────────────
            if (isDirectVideoUrl(url)) {
                downloadDirectVideo(url, videoId, taskId, userId, tempDir);
            } else if (isDouyinUrl(url)) {
                // 抖音：优先用移动端 API（yt-dlp Douyin 提取器目前存在已知 bug）
                downloadViaDouyinApi(url, videoId, taskId, userId, tempDir);
            } else {
                downloadViaYtDlp(url, videoId, taskId, userId, tempDir);
            }

        } catch (Exception e) {
            logger.error("视频下载失败: url={}, videoId={}, error={}", url, videoId, e.getMessage(), e);

            // 提取用户友好的错误信息
            String userMsg = extractUserFriendlyError(e.getMessage());

            // 判断失败类型：下载阶段失败（无文件）vs 分析阶段失败（有文件）
            // 通过检查 Video 记录的 filePath 是否已写入来判断
            String failureType = "DOWNLOAD_FAILED";
            try {
                com.ican.project.model.entity.Video v = videoMapper.selectById(videoId);
                if (v != null && v.getFilePath() != null && !v.getFilePath().isBlank()) {
                    failureType = "ANALYSIS_FAILED";
                }
            } catch (Exception ignored) {}

            // 通过 WebSocket 立即通知前端卡片切换为失败状态
            try {
                TaskProgressWebSocket.sendTaskFailed(userId, taskId, videoId, userMsg, failureType);
            } catch (Exception wsEx) {
                logger.warn("推送失败状态 WS 消息异常: {}", wsEx.getMessage());
            }

            // 标记任务失败
            try {
                analysisTaskService.markTaskFailed(taskId, userMsg, failureType);
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
    
    /**
     * 通过 yt-dlp 下载平台视频（B站、YouTube 等）
     */
    private void downloadViaYtDlp(String url, String videoId, String taskId, String userId, Path tempDir) throws Exception {
        // ── URL 规范化 ────────────────────────────────────────────
        url = normalizeUrl(url);

        // ── Step 1: 获取视频真实标题 ──────────────────────────────
        UrlValidateResult validated = fetchVideoTitleStructured(url);
        String fetchedTitle = validated.valid() ? validated.title() : null;
        if (fetchedTitle == null) {
            logger.warn("获取视频标题失败");
        } else {
            logger.info("获取到视频标题: {}", fetchedTitle);
        }

        // 读取当前 Video 记录，判断用户是否填写了自定义标题
        Video video = videoMapper.selectById(videoId);
        boolean hasUserCustomTitle = video != null
                && video.getTitle() != null
                && !video.getTitle().isBlank()
                && !video.getTitle().startsWith("链接导入 - ");

        String videoTitle;
        if (hasUserCustomTitle) {
            videoTitle = video.getTitle();
            logger.info("用户已设置自定义标题「{}」，保留不覆盖", videoTitle);
        } else {
            if (fetchedTitle != null) {
                videoTitle = fetchedTitle;
            } else {
                videoTitle = "链接导入 - " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                logger.warn("使用保底标题: {}", videoTitle);
            }
            if (video != null) {
                video.setTitle(videoTitle);
                video.setGmtModified(LocalDateTime.now());
                videoMapper.updateById(video);
            }
        }

        TaskProgressWebSocket.sendTaskProgress(
            userId, taskId, videoId,
            "DOWNLOADING", 0, "元数据已获取，准备下载",
            "FETCHING_TITLE", hasUserCustomTitle ? null : videoTitle
        );

        // ── Step 2: 构造安全文件名 ────────────────────────────────
        String safeTitle = sanitizeFileName(videoTitle);
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80).trim();
        String outputTemplate = tempDir.resolve(safeTitle + ".%(ext)s").toString();

        // ── Step 3: 执行 yt-dlp 下载 ─────────────────────────────
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList(
            ytdlpPath,
            "--no-playlist",
            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "--merge-output-format", "mp4",
            "-o", outputTemplate,
            "--no-overwrites",
            "--socket-timeout", "30",
            "--encoding", "utf-8"
        ));
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesFile);
        }
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(tempDir.toFile());

        logger.info("开始 yt-dlp 下载: url={}, videoId={}", url, videoId);

        Process process = pb.start();

        StringBuilder outputLog = new StringBuilder();
        int downloadPhase = 0;
        int lastSentProgress = -1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[yt-dlp] {}", line);
                outputLog.append(line).append("\n");

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
                        int taskProgress;
                        String msg;
                        if (downloadPhase == 0) {
                            taskProgress = (int) (rawProgress * 0.6);
                            msg = "下载视频流: " + (int) rawProgress + "%";
                        } else {
                            taskProgress = 60 + (int) (rawProgress * 0.3);
                            msg = "下载音频流: " + (int) rawProgress + "%";
                        }
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
            String[] lines = outputLog.toString().split("\n");
            String lastError = "";
            for (int i = lines.length - 1; i >= 0 && i >= lines.length - 5; i--) {
                if (!lines[i].trim().isEmpty()) { lastError = lines[i].trim(); break; }
            }
            throw new RuntimeException("yt-dlp 下载失败 (退出码 " + exitCode + "): " + lastError);
        }

        File downloadedFile = findDownloadedFile(tempDir.toFile());
        if (downloadedFile == null) throw new RuntimeException("下载完成但未找到视频文件");

        finishDownload(downloadedFile, videoId, taskId, userId);
    }

    /**
     * 直接 HTTP/HTTPS 流式下载视频文件（适用于以 .mp4/.flv 等结尾的直接 URL）
     */
    private void downloadDirectVideo(String url, String videoId, String taskId, String userId, Path tempDir) throws Exception {
        // 从 URL 路径中提取文件名
        String urlPath = new URL(url).getPath();
        String rawFileName = urlPath.substring(urlPath.lastIndexOf('/') + 1);
        if (rawFileName.isBlank()) rawFileName = "video.mp4";

        // 处理标题
        Video video = videoMapper.selectById(videoId);
        boolean hasUserCustomTitle = video != null
                && video.getTitle() != null
                && !video.getTitle().isBlank()
                && !video.getTitle().startsWith("链接导入 - ");

        String videoTitle;
        if (hasUserCustomTitle) {
            videoTitle = video.getTitle();
        } else {
            // 用文件名（去掉扩展名）作为标题
            int dot = rawFileName.lastIndexOf('.');
            videoTitle = dot > 0 ? rawFileName.substring(0, dot) : rawFileName;
            videoTitle = sanitizeFileName(videoTitle);
            if (videoTitle.isBlank()) {
                videoTitle = "链接导入 - " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            }
            if (video != null) {
                video.setTitle(videoTitle);
                video.setGmtModified(LocalDateTime.now());
                videoMapper.updateById(video);
            }
        }

        TaskProgressWebSocket.sendTaskProgress(
            userId, taskId, videoId,
            "DOWNLOADING", 0, "准备直接下载视频文件",
            "FETCHING_TITLE", hasUserCustomTitle ? null : videoTitle
        );

        // 确定本地保存路径
        String safeFileName = sanitizeFileName(rawFileName);
        if (safeFileName.isBlank()) safeFileName = "video.mp4";
        Path localFile = tempDir.resolve(safeFileName);

        logger.info("开始直接 HTTP 下载: url={}, localFile={}", url, localFile);

        // 建立连接
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.connect();

        int responseCode = conn.getResponseCode();
        if (responseCode / 100 != 2) {
            throw new RuntimeException("HTTP 下载失败，状态码: " + responseCode);
        }

        long totalBytes = conn.getContentLengthLong();
        long downloadedBytes = 0;
        int lastSentProgress = -1;

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(localFile)) {
            byte[] buf = new byte[64 * 1024]; // 64KB 缓冲
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloadedBytes += read;

                if (totalBytes > 0) {
                    int progress = (int) (downloadedBytes * 90.0 / totalBytes);
                    if (progress > lastSentProgress) {
                        lastSentProgress = progress;
                        TaskProgressWebSocket.sendTaskProgress(
                            userId, taskId, videoId,
                            "DOWNLOADING", progress,
                            "下载中: " + String.format("%.1f", downloadedBytes / 1024.0 / 1024.0) + " MB",
                            "DOWNLOADING", null
                        );
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        logger.info("直接下载完成: file={}, size={}", localFile.getFileName(), downloadedBytes);

        File downloadedFile = localFile.toFile();
        if (!downloadedFile.exists() || downloadedFile.length() == 0) {
            throw new RuntimeException("直接下载完成但文件为空或不存在");
        }

        finishDownload(downloadedFile, videoId, taskId, userId);
    }

    /**
     * 公共收尾：上传 MinIO、更新数据库、触发分析流水线
     */
    private void finishDownload(File downloadedFile, String videoId, String taskId, String userId) throws Exception {
        logger.info("视频下载完成: file={}, size={}", downloadedFile.getName(), downloadedFile.length());

        String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String extension = getFileExtension(downloadedFile.getName());
        String objectName = "videos/" + datePath + "/" + IdUtil.fastSimpleUUID() + "." + extension;

        try (InputStream is = new FileInputStream(downloadedFile)) {
            minioService.uploadFile(objectName, is, getContentType(extension), downloadedFile.length());
        }

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

        String currentTaskStatus = analysisTaskService.getTaskStatus(taskId);
        if ("CANCELLED".equals(currentTaskStatus)) {
            logger.info("任务已被取消，跳过触发分析: taskId={}", taskId);
            return;
        }

        String videoUrl = null;
        try { videoUrl = minioService.getFileUrl(objectName); } catch (Exception ignored) {}
        TaskProgressWebSocket.sendTaskProgress(
            userId, taskId, videoId,
            "PENDING", 95, "视频下载完成，等待 AI 分析",
            "PENDING", null, videoUrl
        );
        analysisTaskService.markTaskPending(taskId);

        logger.info("视频下载并上传完成，已自动触发分析: videoId={}, taskId={}", videoId, taskId);
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
     * 结构化 URL 校验：区分 INVALID_URL / UNSUPPORTED / PLATFORM_RESTRICTED / LOGIN_REQUIRED
     */
    @Override
    public VideoDownloadService.UrlValidateResult validateUrlStructured(String url) {
        if (url == null || url.isBlank()) {
            return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "链接不能为空");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "链接格式不正确，请以 http:// 或 https:// 开头");
        }

        // URL 规范化
        url = normalizeUrl(url);

        if (isDirectVideoUrl(url)) {
            // 直接视频文件：HTTP 探测
            try {
                int code = probeUrl(url, "HEAD");
                if (code == 403 || code == 405) code = probeUrl(url, "GET");
                if (code == 403) {
                    return new VideoDownloadService.UrlValidateResult(false, null, "PLATFORM_RESTRICTED", "该链接访问被拒绝（403），可能有防盗链限制");
                }
                if (code == 404) {
                    return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "视频文件不存在（404），请检查链接是否正确");
                }
                if (code / 100 != 2) {
                    return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "链接无法访问（HTTP " + code + "）");
                }
            } catch (Exception e) {
                return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "链接无法访问，请检查网络或链接是否有效");
            }
            // 提取标题
            try {
                String path = new java.net.URL(url).getPath();
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                int dot = fileName.lastIndexOf('.');
                String title = dot > 0 ? fileName.substring(0, dot) : fileName;
                return new VideoDownloadService.UrlValidateResult(true, title.isBlank() ? "直接视频链接" : title, null, null);
            } catch (Exception e) {
                return new VideoDownloadService.UrlValidateResult(true, "直接视频链接", null, null);
            }
        }

        // 平台链接：抖音走专用 API，其他走 yt-dlp
        if (isDouyinUrl(url)) {
            return validateDouyinUrl(url);
        }
        return fetchVideoTitleStructured(url);
    }

    /**
     * 用 yt-dlp --get-title 获取标题，同时解析 stderr 判断错误类型
     */
    private VideoDownloadService.UrlValidateResult fetchVideoTitleStructured(String url) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList(
                ytdlpPath,
                "--get-title",
                "--no-playlist",
                "--socket-timeout", "15",
                "--encoding", "utf-8"
            ));
            if (cookiesFile != null && !cookiesFile.isBlank()) {
                cmd.add("--cookies");
                cmd.add(cookiesFile);
            }
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 收集 stderr
            StringBuilder stderrBuf = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stderrBuf.append(line).append("\n");
                        logger.debug("[yt-dlp stderr] {}", line);
                    }
                } catch (IOException ignored) {}
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            String title = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) title = line.trim();
            }

            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "链接解析超时，请稍后重试");
            }

            if (process.exitValue() == 0 && title != null) {
                return new VideoDownloadService.UrlValidateResult(true, title, null, null);
            }

            // 解析 stderr 判断错误类型
            String stderr = stderrBuf.toString();
            return classifyYtDlpError(stderr);

        } catch (Exception e) {
            logger.warn("yt-dlp 结构化校验异常: {}", e.getMessage());
            return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "链接验证失败，请检查网络");
        }
    }

    /**
     * 根据 yt-dlp stderr 输出判断错误类型
     */
    private VideoDownloadService.UrlValidateResult classifyYtDlpError(String stderr) {
        if (stderr == null) stderr = "";
        String s = stderr.toLowerCase();

        // 需要登录 / Cookie
        if (s.contains("cookies") || s.contains("sign in") || s.contains("login")
                || s.contains("fresh cookies") || s.contains("not logged in")) {
            return new VideoDownloadService.UrlValidateResult(
                false, null, "LOGIN_REQUIRED",
                "该平台需要登录验证，请配置 Cookies 后重试"
            );
        }
        // 地区限制 / 403
        if (s.contains("http error 403") || s.contains("403 forbidden")
                || s.contains("geo") || s.contains("region") || s.contains("country")) {
            return new VideoDownloadService.UrlValidateResult(
                false, null, "PLATFORM_RESTRICTED",
                "该内容有地区或访问限制（403），可能需要代理或 Cookies"
            );
        }
        // 视频不可用
        if (s.contains("video unavailable") || s.contains("private video")
                || s.contains("http error 404") || s.contains("404")) {
            return new VideoDownloadService.UrlValidateResult(
                false, null, "INVALID_URL",
                "视频不可用或已被删除，请检查链接是否正确"
            );
        }
        // 不支持的平台
        if (s.contains("unsupported url") || s.contains("no suitable extractor")) {
            return new VideoDownloadService.UrlValidateResult(
                false, null, "UNSUPPORTED",
                "暂不支持该平台或链接格式"
            );
        }
        // 兜底
        return new VideoDownloadService.UrlValidateResult(
            false, null, "INVALID_URL",
            "无法解析该链接，请检查链接是否有效"
        );
    }

    /**
     * 保存 Cookies 文本到 cookies.txt 文件
     */
    @Override
    public void saveCookies(String cookiesContent) throws java.io.IOException {
        if (cookiesFile == null || cookiesFile.isBlank()) {
            // 未配置路径时，默认写到 yt-dlp 同目录
            String ytdlpDir = Paths.get(ytdlpPath).getParent() != null
                ? Paths.get(ytdlpPath).getParent().toString()
                : System.getProperty("user.home");
            cookiesFile = ytdlpDir + java.io.File.separator + "cookies.txt";
            logger.info("cookies-file 未配置，自动写入: {}", cookiesFile);
        }
        Path cookiesPath = Paths.get(cookiesFile);
        Files.createDirectories(cookiesPath.getParent());
        Files.writeString(cookiesPath, cookiesContent, StandardCharsets.UTF_8);
        logger.info("Cookies 已保存: {}", cookiesFile);
    }

    /**
     * 预校验 URL 是否可被处理（同步，用于提交前拦截）
     * 委托给 validateUrlStructured，保持向后兼容
     */
    @Override
    public String validateUrl(String url) {
        UrlValidateResult result = validateUrlStructured(url);
        return result.valid() ? result.title() : null;
    }
    /**
     * 向目标 URL 发送指定方法的探测请求，返回 HTTP 状态码
     * GET 请求时附加 Range: bytes=0-0，只取第一个字节，避免下载整个文件
     */
    private int probeUrl(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setInstanceFollowRedirects(true);
        if ("GET".equals(method)) {
            conn.setRequestProperty("Range", "bytes=0-0");
        }
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 判断是否为抖音链接
     */
    private boolean isDouyinUrl(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URI(url).getHost();
            return host != null && (host.contains("douyin.com") || host.contains("iesdouyin.com"));
        } catch (Exception e) { return false; }
    }

    /**
     * 从抖音 URL 中提取视频 ID（支持 /video/xxx 和 modal_id=xxx 格式）
     */
    private String extractDouyinVideoId(String url) {
        // /video/7605535940341239082
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/video/(\\d+)").matcher(url);
        if (m.find()) return m.group(1);
        // modal_id=7605535940341239082
        m = java.util.regex.Pattern.compile("[?&]modal_id=(\\d+)").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 抖音专用下载：调用移动端 API 获取无水印视频直链，再直接 HTTP 下载
     * 绕过 yt-dlp Douyin 提取器的已知 bug（API 返回空 JSON）
     */
    private void downloadViaDouyinApi(String url, String videoId, String taskId, String userId, Path tempDir) throws Exception {
        String awemeId = extractDouyinVideoId(normalizeUrl(url));
        if (awemeId == null) {
            throw new RuntimeException("无法从抖音链接中提取视频 ID，请检查链接格式");
        }

        logger.info("抖音专用 API 下载: awemeId={}", awemeId);

        // ── Step 1: 调用移动端 API 获取视频信息 ──────────────────
        String apiUrl = "https://api.amemv.com/aweme/v1/feed/?aweme_id=" + awemeId
                + "&version_code=160904&app_name=aweme";
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
            "com.ss.android.ugc.aweme/160904 (Linux; U; Android 10; zh_CN; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)");
        conn.setRequestProperty("Accept", "application/json");

        String jsonBody;
        try (InputStream is = conn.getInputStream()) {
            jsonBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }

        // 简单 JSON 解析（避免引入额外依赖）
        DouyinVideoInfo info = parseDouyinApiResponse(jsonBody, awemeId);
        if (info == null) {
            // API 失败，fallback 到 yt-dlp
            logger.warn("抖音 API 解析失败，fallback 到 yt-dlp: awemeId={}", awemeId);
            downloadViaYtDlp(url, videoId, taskId, userId, tempDir);
            return;
        }

        logger.info("抖音 API 获取到标题: {}", info.title);

        // ── Step 2: 更新标题 ──────────────────────────────────────
        Video video = videoMapper.selectById(videoId);
        boolean hasUserCustomTitle = video != null
                && video.getTitle() != null
                && !video.getTitle().isBlank()
                && !video.getTitle().startsWith("链接导入 - ");

        String videoTitle;
        if (hasUserCustomTitle) {
            videoTitle = video.getTitle();
        } else {
            videoTitle = info.title != null && !info.title.isBlank()
                ? info.title
                : "抖音视频 - " + awemeId;
            if (video != null) {
                video.setTitle(videoTitle);
                video.setGmtModified(LocalDateTime.now());
                videoMapper.updateById(video);
            }
        }

        TaskProgressWebSocket.sendTaskProgress(
            userId, taskId, videoId,
            "DOWNLOADING", 0, "元数据已获取，准备下载",
            "FETCHING_TITLE", hasUserCustomTitle ? null : videoTitle
        );

        // ── Step 3: 下载视频 ──────────────────────────────────────
        String safeTitle = sanitizeFileName(videoTitle);
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80).trim();
        Path localFile = tempDir.resolve(safeTitle + ".mp4");

        logger.info("开始下载抖音视频: url={}", info.videoUrl.substring(0, Math.min(80, info.videoUrl.length())));

        HttpURLConnection dlConn = (HttpURLConnection) new URL(info.videoUrl).openConnection();
        dlConn.setConnectTimeout(15_000);
        dlConn.setReadTimeout(120_000);
        dlConn.setRequestProperty("User-Agent",
            "com.ss.android.ugc.aweme/160904 (Linux; U; Android 10; zh_CN; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)");
        dlConn.setRequestProperty("Referer", "https://www.douyin.com/");
        dlConn.setInstanceFollowRedirects(true);

        int respCode = dlConn.getResponseCode();
        if (respCode / 100 != 2) {
            dlConn.disconnect();
            throw new RuntimeException("抖音视频下载失败，HTTP " + respCode);
        }

        long totalBytes = dlConn.getContentLengthLong();
        long downloadedBytes = 0;
        int lastSentProgress = -1;

        try (InputStream in = dlConn.getInputStream();
             OutputStream out = Files.newOutputStream(localFile)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloadedBytes += read;
                if (totalBytes > 0) {
                    int progress = (int) (downloadedBytes * 90.0 / totalBytes);
                    if (progress > lastSentProgress) {
                        lastSentProgress = progress;
                        TaskProgressWebSocket.sendTaskProgress(
                            userId, taskId, videoId,
                            "DOWNLOADING", progress,
                            "下载中: " + String.format("%.1f", downloadedBytes / 1024.0 / 1024.0) + " MB",
                            "DOWNLOADING", null
                        );
                    }
                }
            }
        } finally {
            dlConn.disconnect();
        }

        File downloadedFile = localFile.toFile();
        if (!downloadedFile.exists() || downloadedFile.length() == 0) {
            throw new RuntimeException("抖音视频下载完成但文件为空");
        }

        logger.info("抖音视频下载完成: size={}", downloadedFile.length());
        finishDownload(downloadedFile, videoId, taskId, userId);
    }

    /** 抖音视频信息 */
    private record DouyinVideoInfo(String title, String videoUrl) {}

    /**
     * 解析抖音移动端 API 响应，提取标题和视频直链
     * 使用正则避免引入 JSON 库依赖
     */
    private DouyinVideoInfo parseDouyinApiResponse(String json, String awemeId) {
        try {
            // 提取 desc（标题）
            java.util.regex.Matcher descM = java.util.regex.Pattern
                .compile("\"desc\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
            String title = descM.find() ? descM.group(1) : null;

            // 优先取 download_addr（无水印），其次 play_addr
            String videoUrl = extractDouyinVideoUrl(json, "download_addr");
            if (videoUrl == null) {
                videoUrl = extractDouyinVideoUrl(json, "play_addr");
            }
            if (videoUrl == null) return null;

            // 反转义 unicode
            if (title != null) {
                title = title.replace("\\u0026", "&").replace("\\n", " ").trim();
            }

            return new DouyinVideoInfo(title, videoUrl);
        } catch (Exception e) {
            logger.warn("解析抖音 API 响应失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractDouyinVideoUrl(String json, String addrKey) {
        // 找到 "download_addr" 或 "play_addr" 块，再从 url_list 取第一个 URL
        int idx = json.indexOf("\"" + addrKey + "\"");
        if (idx < 0) return null;
        int urlListIdx = json.indexOf("\"url_list\"", idx);
        if (urlListIdx < 0) return null;
        int arrStart = json.indexOf('[', urlListIdx);
        if (arrStart < 0) return null;
        int firstQuote = json.indexOf('"', arrStart + 1);
        if (firstQuote < 0) return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;
        String raw = json.substring(firstQuote + 1, secondQuote);
        // 反转义 \/ → /
        return raw.replace("\\/", "/");
    }

    /**
     * 抖音专用：获取视频标题（用于 validateUrl 校验阶段）
     */
    private VideoDownloadService.UrlValidateResult validateDouyinUrl(String url) {
        String awemeId = extractDouyinVideoId(url);
        if (awemeId == null) {
            return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "无法从抖音链接中提取视频 ID");
        }
        try {
            String apiUrl = "https://api.amemv.com/aweme/v1/feed/?aweme_id=" + awemeId
                    + "&version_code=160904&app_name=aweme";
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent",
                "com.ss.android.ugc.aweme/160904 (Linux; U; Android 10; zh_CN; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)");
            String json;
            try (InputStream is = conn.getInputStream()) {
                json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
            DouyinVideoInfo info = parseDouyinApiResponse(json, awemeId);
            if (info == null) {
                return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "视频不存在或已被删除");
            }
            String title = info.title != null && !info.title.isBlank() ? info.title : "抖音视频 " + awemeId;
            return new VideoDownloadService.UrlValidateResult(true, title, null, null);
        } catch (Exception e) {
            logger.warn("抖音 API 校验失败: {}", e.getMessage());
            return new VideoDownloadService.UrlValidateResult(false, null, "INVALID_URL", "抖音链接验证失败，请检查链接是否有效");
        }
    }

    /**
     * 规范化视频平台 URL（目前主要处理抖音 modal_id 格式，供 yt-dlp 路径使用）
     *   抖音 /jingxuan?modal_id=xxx  →  /video/xxx
     */
    private String normalizeUrl(String url) {
        if (url == null) return url;
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            String path = uri.getPath();
            String query = uri.getQuery();

            // 抖音：从 modal_id 参数提取视频 ID
            if (host != null && host.contains("douyin.com")
                    && query != null && query.contains("modal_id=")) {
                String modalId = null;
                for (String param : query.split("&")) {
                    if (param.startsWith("modal_id=")) {
                        modalId = param.substring("modal_id=".length());
                        break;
                    }
                }
                if (modalId != null && !modalId.isBlank()) {
                    String normalized = "https://www.douyin.com/video/" + modalId;
                    logger.info("抖音 URL 规范化: {} → {}", url, normalized);
                    return normalized;
                }
            }
        } catch (Exception e) {
            logger.warn("URL 规范化失败，使用原始 URL: {}", e.getMessage());
        }
        return url;
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
        if (rawMsg.contains("HTTP 下载失败，状态码: 403")) return "访问被拒绝（403），该资源可能有防盗链限制";
        if (rawMsg.contains("HTTP 下载失败，状态码: 404")) return "视频文件不存在（404）";
        if (rawMsg.contains("HTTP 下载失败")) return "直接下载失败，请检查链接是否有效";
        if (rawMsg.contains("文件为空")) return "下载的文件为空，请检查链接是否有效";
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
