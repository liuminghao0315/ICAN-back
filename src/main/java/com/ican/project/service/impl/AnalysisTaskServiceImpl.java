package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.config.RabbitMQConfig;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.AnalysisResultMapper;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.mapper.UserMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.dto.AnalysisTaskDTO;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.AnalysisTaskVO;
import com.ican.project.service.AnalysisTaskService;
import com.ican.project.service.FolderService;
import com.ican.project.service.MinioService;
import com.ican.project.service.VideoService;
import com.ican.project.utils.RedisCacheUtil;
import com.ican.project.websocket.TaskProgressWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分析任务服务实现
 */
@Service
public class AnalysisTaskServiceImpl implements AnalysisTaskService {
    
    private static final Logger logger = LoggerFactory.getLogger(AnalysisTaskServiceImpl.class);
    
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    
    @Autowired
    private AnalysisResultMapper analysisResultMapper;
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private VideoService videoService;
    
    @Autowired
    private FolderService folderService;
    
    @Autowired
    private MinioService minioService;
    
    @Autowired
    private RedisCacheUtil redisCacheUtil;
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Override
    @Transactional
    public AnalysisTaskVO createTask(AnalysisTaskDTO dto, String userId) {
        // 验证视频存在且属于当前用户
        Video video = videoMapper.selectById(dto.getVideoId());
        if (video == null) {
            throw new BusinessException("视频不存在");
        }
        if (video.getUserId() == null || !video.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该视频");
        }
        
        // 确定任务类型
        String taskType = dto.getTaskType();
        if (taskType == null || taskType.isEmpty()) {
            taskType = AnalysisTask.TaskType.FULL_ANALYSIS.name();
        } else {
            try {
                AnalysisTask.TaskType.valueOf(taskType);
            } catch (IllegalArgumentException e) {
                throw new BusinessException("无效的任务类型: " + taskType);
            }
        }
        
        // ── 幂等处理：1:1 约束 ──────────────────────────────────────────────
        // 查找该 videoId 下已有的任意 Task（无论状态）
        LambdaQueryWrapper<AnalysisTask> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(AnalysisTask::getVideoId, dto.getVideoId())
                .orderByDesc(AnalysisTask::getGmtCreated)
                .last("LIMIT 1");
        AnalysisTask existingTask = analysisTaskMapper.selectOne(existingWrapper);
        
        AnalysisTask task;
        if (existingTask != null) {
            // 如果已有任务正在处理中，且不是强制重启，则拒绝
            String existingStatus = existingTask.getStatus();
            boolean isActive = AnalysisTask.Status.PENDING.name().equals(existingStatus)
                    || AnalysisTask.Status.PROCESSING.name().equals(existingStatus);
            if (isActive && !Boolean.TRUE.equals(dto.getForceRestart())) {
                throw new BusinessException("该视频已有正在处理的分析任务");
            }
            
            // 就地重置：复用同一条记录，不 INSERT 新行
            existingTask.setStatus(AnalysisTask.Status.PENDING.name());
            existingTask.setProgress(0);
            existingTask.setErrorMessage(null);
            existingTask.setStartedAt(null);
            existingTask.setCompletedAt(null);
            existingTask.setTaskType(taskType);
            existingTask.setGmtModified(LocalDateTime.now());
            analysisTaskMapper.updateById(existingTask);
            task = existingTask;
            logger.info("幂等重置分析任务: taskId={}, videoId={}, 原状态={}", task.getId(), dto.getVideoId(), existingStatus);
        } else {
            // 首次创建
            task = AnalysisTask.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .videoId(dto.getVideoId())
                    .userId(userId)
                    .taskType(taskType)
                    .status(AnalysisTask.Status.PENDING.name())
                    .progress(0)
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now())
                    .build();
            analysisTaskMapper.insert(task);
            // 首次创建才增加累计分析次数
            userMapper.incrementAnalysisCount(userId);
            logger.info("创建分析任务成功: taskId={}, videoId={}, taskType={}", task.getId(), dto.getVideoId(), taskType);
        }
        // ────────────────────────────────────────────────────────────────────
        
        // 清除相关缓存
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_LIST + userId + ":*");
        
        // 更新视频状态为分析中
        videoService.updateVideoStatus(dto.getVideoId(), Video.Status.ANALYZING.name());
        
        return convertToVO(task, video, null);
    }
    
    @Override
    public AnalysisTaskVO getTaskById(String taskId, String userId) {
        // 先从缓存获取
        String cacheKey = RedisCacheUtil.CacheKey.TASK_BY_ID + taskId + ":" + userId;
        AnalysisTaskVO cachedTask = redisCacheUtil.get(cacheKey, AnalysisTaskVO.class);
        if (cachedTask != null) {
            logger.debug("从缓存获取任务: taskId={}", taskId);
            return cachedTask;
        }
        
        // 缓存未命中，查询数据库
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (task.getUserId() == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该任务");
        }
        
        // 获取视频信息
        Video video = null;
        if (task.getVideoId() != null) {
            video = videoMapper.selectById(task.getVideoId());
        }
        AnalysisResult result = getResultByTaskId(taskId);
        
        AnalysisTaskVO taskVO = convertToVO(task, video, result);
        
        // 存入缓存
        redisCacheUtil.set(cacheKey, taskVO);
        return taskVO;
    }
    
    @Override
    public AnalysisTaskVO getLatestTaskByVideoId(String videoId, String userId) {
        // 验证视频权限
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在");
        }
        if (video.getUserId() == null || !video.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该视频");
        }
        
        // 查询最新任务
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getVideoId, videoId)
                .orderByDesc(AnalysisTask::getGmtCreated)
                .last("LIMIT 1");
        AnalysisTask task = analysisTaskMapper.selectOne(wrapper);
        
        if (task == null) {
            return null;
        }
        
        AnalysisResult result = getResultByTaskId(task.getId());
        return convertToVO(task, video, result);
    }
    
    @Override
    public Page<AnalysisTaskVO> getUserTasks(String userId, String status, String riskLevel, int page, int size, String sortBy, String sortOrder) {
        return getUserTasks(userId, status, riskLevel, page, size, sortBy, sortOrder, null);
    }

    @Override
    public Page<AnalysisTaskVO> getUserTasks(String userId, String status, String riskLevel, int page, int size, String sortBy, String sortOrder, String folderId) {
        // 使用 JOIN 查询，在数据库层面进行全局排序和筛选
        String effectiveStatus = (status != null && !status.isEmpty()) ? status.toUpperCase() : null;
        String effectiveRiskLevel = (riskLevel != null && !riskLevel.isEmpty()) ? riskLevel.toUpperCase() : null;
        String effectiveSortBy = sortBy != null ? sortBy : "gmtCreated";
        String effectiveSortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        
        // 处理文件夹过滤
        java.util.List<String> folderIds = null;
        boolean uncategorizedOnly = false;
        if (folderId != null && !folderId.isEmpty()) {
            if ("__UNCATEGORIZED__".equals(folderId)) {
                uncategorizedOnly = true;
            } else if (!"__ALL__".equals(folderId)) {
                // 具体文件夹：获取该文件夹及所有子文件夹ID
                folderIds = new java.util.ArrayList<>();
                folderIds.add(folderId);
                folderIds.addAll(folderService.getAllDescendantFolderIds(userId, folderId));
            }
            // __ALL__ 不加任何过滤
        }
        
        // 直接查询数据库，不使用缓存，确保数据准确性（特别是 total 总数）
        Page<java.util.Map<String, Object>> taskPage = new Page<>(page, size);
        
        Page<java.util.Map<String, Object>> result = analysisTaskMapper.selectTasksWithJoin(
                taskPage, userId, effectiveStatus, effectiveRiskLevel, effectiveSortBy, effectiveSortOrder, folderIds, uncategorizedOnly);
        
        // 转换为VO
        java.util.List<AnalysisTaskVO> voList = result.getRecords().stream()
                .map(this::convertMapToVO)
                .collect(Collectors.toList());
        
        Page<AnalysisTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        
        return voPage;
    }
    
    /**
     * 将 Map 结果转换为 AnalysisTaskVO
     */
    private AnalysisTaskVO convertMapToVO(java.util.Map<String, Object> map) {
        AnalysisTaskVO.AnalysisTaskVOBuilder builder = AnalysisTaskVO.builder()
                .id((String) map.get("id"))
                .videoId((String) map.get("video_id"))
                .taskType((String) map.get("task_type"))
                .status((String) map.get("status"))
                .progress(map.get("progress") != null ? ((Number) map.get("progress")).intValue() : 0)
                .errorMessage((String) map.get("error_message"));
        
        // 时间字段
        if (map.get("started_at") != null) {
            builder.startedAt((LocalDateTime) map.get("started_at"));
        }
        if (map.get("completed_at") != null) {
            builder.completedAt((LocalDateTime) map.get("completed_at"));
        }
        if (map.get("gmt_created") != null) {
            builder.gmtCreated((LocalDateTime) map.get("gmt_created"));
        }
        
        // 视频信息
        String videoTitle = (String) map.get("video_title");
        if (videoTitle != null) {
            builder.videoTitle(videoTitle);
        }
        if (map.get("video_duration") != null) {
            builder.videoDuration(((Number) map.get("video_duration")).doubleValue());
        }
        String videoFilePath = (String) map.get("video_file_path");
        if (videoFilePath != null) {
            builder.videoUrl(minioService.getFileUrl(videoFilePath));
        }
        // 缩略图
        String videoThumbnailPath = (String) map.get("video_thumbnail_path");
        if (videoThumbnailPath != null && !videoThumbnailPath.isEmpty()) {
            builder.thumbnailUrl(minioService.getFileUrl(videoThumbnailPath));
        }
        
        // 来源信息
        String videoSourceType = (String) map.get("video_source_type");
        if (videoSourceType != null) {
            builder.sourceType(videoSourceType);
        }
        String videoSourceUrl = (String) map.get("video_source_url");
        if (videoSourceUrl != null) {
            builder.sourceUrl(videoSourceUrl);
        }

        // 文件夹归属信息
        String videoFolderId = (String) map.get("video_folder_id");
        if (videoFolderId != null) {
            builder.folderId(videoFolderId);
        }
        String videoFolderName = (String) map.get("video_folder_name");
        if (videoFolderName != null) {
            builder.folderName(videoFolderName);
        }

        // 收藏状态
        Object isFavoritedVal = map.get("is_favorited");
        if (isFavoritedVal != null) {
            builder.isFavorited(((Number) isFavoritedVal).intValue() == 1);
        }
        
        // 分析结果信息
        String resultId = (String) map.get("result_id");
        if (resultId != null) {
            builder.hasResult(true)
                    .resultId(resultId);
            
            // 从数据库查询完整的AnalysisResult以获取摘要信息
            try {
                AnalysisResult result = analysisResultMapper.selectById(resultId);
                if (result != null) {
                    // 提取风险信息（从 opinionRiskFusion）
                    String opinionRiskFusion = result.getOpinionRiskFusion();
                    if (opinionRiskFusion != null && !opinionRiskFusion.isEmpty()) {
                        com.alibaba.fastjson2.JSONObject fusion = com.alibaba.fastjson2.JSON.parseObject(opinionRiskFusion);
                        Integer finalScore = fusion.getInteger("finalScore");
                        if (finalScore != null) {
                            Double riskScore = finalScore / 100.0;
                            builder.riskScore(riskScore);
                            
                            // 根据分数计算风险等级
                            if (finalScore >= 67) {
                                builder.riskLevel("HIGH");
                            } else if (finalScore >= 34) {
                                builder.riskLevel("MEDIUM");
                            } else {
                                builder.riskLevel("LOW");
                            }
                        }
                    }
                    
                    // 提取情感信息（从 attitudeEvidences 统计）
                    String attitudeEvidences = result.getAttitudeEvidences();
                    if (attitudeEvidences != null && !attitudeEvidences.isEmpty()) {
                        com.alibaba.fastjson2.JSONArray evidences = com.alibaba.fastjson2.JSON.parseArray(attitudeEvidences);
                        if (evidences != null && !evidences.isEmpty()) {
                            int positive = 0, neutral = 0, negative = 0;
                            for (Object obj : evidences) {
                                if (obj instanceof com.alibaba.fastjson2.JSONObject) {
                                    com.alibaba.fastjson2.JSONObject ev = (com.alibaba.fastjson2.JSONObject) obj;
                                    Integer sentimentScore = ev.getInteger("sentimentScore");
                                    if (sentimentScore != null) {
                                        if (sentimentScore < 33) {
                                            positive++;
                                        } else if (sentimentScore > 67) {
                                            negative++;
                                        } else {
                                            neutral++;
                                        }
                                    }
                                }
                            }
                            
                            // 根据占比最大的类别设置情感标签
                            if (negative >= positive && negative >= neutral) {
                                builder.sentimentLabel("NEGATIVE");
                            } else if (positive >= neutral) {
                                builder.sentimentLabel("POSITIVE");
                            } else {
                                builder.sentimentLabel("NEUTRAL");
                            }
                        }
                    }
                    
                    // 提取关键词、高校、主题
                    String detectedKeywords = result.getDetectedKeywords();
                    if (detectedKeywords != null && !detectedKeywords.isEmpty()) {
                        com.alibaba.fastjson2.JSONArray kwArr = com.alibaba.fastjson2.JSON.parseArray(detectedKeywords);
                        java.util.List<String> kwList = new java.util.ArrayList<>();
                        for (Object obj : kwArr) {
                            if (obj instanceof com.alibaba.fastjson2.JSONObject) {
                                String word = ((com.alibaba.fastjson2.JSONObject) obj).getString("word");
                                if (word != null && !word.isEmpty()) kwList.add(word);
                            }
                            if (kwList.size() >= 5) break;
                        }
                        if (!kwList.isEmpty()) builder.keywords(kwList);
                    }
                    if (result.getUniversityName() != null && !result.getUniversityName().isEmpty()) {
                        builder.universityName(result.getUniversityName());
                    }
                    if (result.getTopicCategory() != null) {
                        builder.topicCategory(result.getTopicCategory());
                    }
                }
            } catch (Exception e) {
                logger.warn("提取分析结果摘要失败: resultId={}, error={}", resultId, e.getMessage());
            }
        } else {
            builder.hasResult(false);
        }
        
        return builder.build();
    }
    
    @Override
    @Transactional
    public void cancelTask(String taskId, String userId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (task.getUserId() == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该任务");
        }
        
        // 只能取消等待中或处理中的任务
        String currentStatus = task.getStatus();
        if (!AnalysisTask.Status.PENDING.name().equals(currentStatus) 
                && !AnalysisTask.Status.PROCESSING.name().equals(currentStatus)) {
            throw new BusinessException("只能取消等待中或处理中的任务");
        }
        
        // 更新任务状态
        task.setStatus(AnalysisTask.Status.CANCELLED.name());
        task.setGmtModified(LocalDateTime.now());
        analysisTaskMapper.updateById(task);
        
        // 更新视频状态：下载中取消 → FAILED（文件未完整），其他取消 → UPLOADED
        String videoStatus = AnalysisTask.Status.DOWNLOADING.name().equals(currentStatus)
                ? Video.Status.FAILED.name()
                : Video.Status.UPLOADED.name();
        videoService.updateVideoStatus(task.getVideoId(), videoStatus);
        
        // 向 Python 发送取消信号，物理停止正在运行的分析进程
        sendCancelSignal(taskId);
        
        logger.info("取消分析任务: taskId={}", taskId);
    }
    
    @Override
    @Transactional
    public AnalysisTaskVO retryTask(String taskId, String userId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (task.getUserId() == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该任务");
        }
        
        // 只允许重试失败或已取消的任务，已完成/排队中/分析中均不允许
        String currentStatus = task.getStatus();
        if (!AnalysisTask.Status.FAILED.name().equals(currentStatus)
                && !AnalysisTask.Status.CANCELLED.name().equals(currentStatus)) {
            throw new BusinessException("当前状态不允许重新分析");
        }
        
        // 清理脏数据：删除该任务之前产生的所有残留分析结果
        LambdaQueryWrapper<AnalysisResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.eq(AnalysisResult::getTaskId, taskId);
        int deleted = analysisResultMapper.delete(resultWrapper);
        logger.info("重新分析前清理旧分析结果: taskId={}, 删除记录数={}", taskId, deleted);
        
        // 就地重置：复用同一条 Task 记录，不产生新行
        task.setStatus(AnalysisTask.Status.PENDING.name());
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setGmtModified(LocalDateTime.now());
        analysisTaskMapper.updateById(task);
        
        // 清除相关缓存
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_LIST + userId + ":*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_BY_ID + taskId + ":*");
        
        // 重置视频状态为分析中（ANALYZING），等待处理器拾取
        videoService.updateVideoStatus(task.getVideoId(), Video.Status.ANALYZING.name());
        
        Video video = videoMapper.selectById(task.getVideoId());
        logger.info("就地重置分析任务: taskId={}, videoId={}", taskId, task.getVideoId());
        return convertToVO(task, video, null);
    }
    
    @Override
    @Transactional
    public void updateTaskStatus(String taskId, String status, Integer progress, String errorMessage) {
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .set(AnalysisTask::getStatus, status)
                .set(AnalysisTask::getGmtModified, LocalDateTime.now());
        
        if (progress != null) {
            wrapper.set(AnalysisTask::getProgress, progress);
        }
        if (errorMessage != null) {
            wrapper.set(AnalysisTask::getErrorMessage, errorMessage);
        }
        
        analysisTaskMapper.update(null, wrapper);
        
        // 清除相关缓存
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task != null) {
            redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_BY_ID + taskId + ":*");
            if (task.getUserId() != null) {
                redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_LIST + task.getUserId() + ":*");
            }
        }
        
        logger.debug("更新任务状态: taskId={}, status={}, progress={}", taskId, status, progress);
    }
    
    @Override
    @Transactional
    public void markTaskProcessing(String taskId) {
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .set(AnalysisTask::getStatus, AnalysisTask.Status.PROCESSING.name())
                .set(AnalysisTask::getStartedAt, LocalDateTime.now())
                .set(AnalysisTask::getGmtModified, LocalDateTime.now());
        
        analysisTaskMapper.update(null, wrapper);
        logger.info("任务开始处理: taskId={}", taskId);
    }
    
    @Override
    @Transactional
    public void markTaskCompleted(String taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            logger.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .set(AnalysisTask::getStatus, AnalysisTask.Status.COMPLETED.name())
                .set(AnalysisTask::getProgress, 100)
                .set(AnalysisTask::getCompletedAt, LocalDateTime.now())
                .set(AnalysisTask::getGmtModified, LocalDateTime.now());
        
        analysisTaskMapper.update(null, wrapper);
        
        // 更新视频状态
        videoService.updateVideoStatus(task.getVideoId(), Video.Status.COMPLETED.name());
        
        logger.info("任务完成: taskId={}", taskId);
    }
    
    @Override
    @Transactional
    public void markTaskFailed(String taskId, String errorMessage) {
        markTaskFailed(taskId, errorMessage, "ANALYSIS_FAILED");
    }

    @Override
    @Transactional
    public void markTaskFailed(String taskId, String errorMessage, String failureType) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            logger.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .set(AnalysisTask::getStatus, AnalysisTask.Status.FAILED.name())
                .set(AnalysisTask::getErrorMessage, errorMessage)
                .set(AnalysisTask::getFailureType, failureType)
                .set(AnalysisTask::getCompletedAt, LocalDateTime.now())
                .set(AnalysisTask::getGmtModified, LocalDateTime.now());
        
        analysisTaskMapper.update(null, wrapper);
        
        // 更新视频状态
        videoService.updateVideoStatus(task.getVideoId(), Video.Status.FAILED.name());
        
        logger.error("任务失败: taskId={}, failureType={}, error={}", taskId, failureType, errorMessage);
    }
    
    @Override
    public AnalysisTaskVO getPendingTask() {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getStatus, AnalysisTask.Status.PENDING.name())
                .orderByAsc(AnalysisTask::getGmtCreated)
                .last("LIMIT 1");
        
        AnalysisTask task = analysisTaskMapper.selectOne(wrapper);
        if (task == null) {
            return null;
        }
        
        Video video = videoMapper.selectById(task.getVideoId());
        return convertToVO(task, video, null);
    }
    
    /**
     * 根据任务ID获取分析结果
     */
    private AnalysisResult getResultByTaskId(String taskId) {
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisResult::getTaskId, taskId);
        return analysisResultMapper.selectOne(wrapper);
    }
    
    /**
     * 转换为VO
     */
    private AnalysisTaskVO convertToVO(AnalysisTask task, Video video, AnalysisResult result) {
        AnalysisTaskVO.AnalysisTaskVOBuilder builder = AnalysisTaskVO.builder()
                .id(task.getId())
                .videoId(task.getVideoId())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .progress(task.getProgress())
                .errorMessage(task.getErrorMessage())
                .failureType(task.getFailureType())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .gmtCreated(task.getGmtCreated());
        
        if (video != null) {
            builder.videoTitle(video.getTitle())
                    .videoUrl(minioService.getFileUrl(video.getFilePath()))
                    .videoDuration(video.getDuration());
        }
        
        if (result != null) {
            builder.hasResult(true)
                    .resultId(result.getId());
            
            // 提取风险信息（从 opinionRiskFusion）
            String opinionRiskFusion = result.getOpinionRiskFusion();
            if (opinionRiskFusion != null && !opinionRiskFusion.isEmpty()) {
                try {
                    com.alibaba.fastjson2.JSONObject fusion = com.alibaba.fastjson2.JSON.parseObject(opinionRiskFusion);
                    Integer finalScore = fusion.getInteger("finalScore");
                    if (finalScore != null) {
                        builder.riskScore(finalScore / 100.0);
                        if (finalScore >= 67) {
                            builder.riskLevel("HIGH");
                        } else if (finalScore >= 34) {
                            builder.riskLevel("MEDIUM");
                        } else {
                            builder.riskLevel("LOW");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("提取风险信息失败: resultId={}, error={}", result.getId(), e.getMessage());
                }
            }
            
            // 提取情感信息（从 attitudeEvidences 统计）
            String attitudeEvidences = result.getAttitudeEvidences();
            if (attitudeEvidences != null && !attitudeEvidences.isEmpty()) {
                try {
                    com.alibaba.fastjson2.JSONArray evidences = com.alibaba.fastjson2.JSON.parseArray(attitudeEvidences);
                    if (evidences != null && !evidences.isEmpty()) {
                        int positive = 0, neutral = 0, negative = 0;
                        for (Object obj : evidences) {
                            if (obj instanceof com.alibaba.fastjson2.JSONObject) {
                                com.alibaba.fastjson2.JSONObject ev = (com.alibaba.fastjson2.JSONObject) obj;
                                Integer sentimentScore = ev.getInteger("sentimentScore");
                                if (sentimentScore != null) {
                                    if (sentimentScore < 33) positive++;
                                    else if (sentimentScore > 67) negative++;
                                    else neutral++;
                                }
                            }
                        }
                        if (negative >= positive && negative >= neutral) {
                            builder.sentimentLabel("NEGATIVE");
                        } else if (positive >= neutral) {
                            builder.sentimentLabel("POSITIVE");
                        } else {
                            builder.sentimentLabel("NEUTRAL");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("提取情感信息失败: resultId={}, error={}", result.getId(), e.getMessage());
                }
            }
        } else {
            builder.hasResult(false);
        }
        
        // 添加来源信息 + 缩略图 + 关键词 + 高校
        if (video != null) {
            builder.sourceType(video.getSourceType())
                    .sourceUrl(video.getSourceUrl());
            // 缩略图
            if (video.getThumbnailPath() != null && !video.getThumbnailPath().isEmpty()) {
                try {
                    builder.thumbnailUrl(minioService.getFileUrl(video.getThumbnailPath()));
                } catch (Exception ignored) {}
            }
        }
        
        // 从分析结果提取关键词、高校、主题
        if (result != null) {
            // 关键词
            String detectedKeywords = result.getDetectedKeywords();
            if (detectedKeywords != null && !detectedKeywords.isEmpty()) {
                try {
                    com.alibaba.fastjson2.JSONArray kwArr = com.alibaba.fastjson2.JSON.parseArray(detectedKeywords);
                    java.util.List<String> kwList = new java.util.ArrayList<>();
                    for (Object obj : kwArr) {
                        if (obj instanceof com.alibaba.fastjson2.JSONObject) {
                            String word = ((com.alibaba.fastjson2.JSONObject) obj).getString("word");
                            if (word != null && !word.isEmpty()) kwList.add(word);
                        }
                        if (kwList.size() >= 5) break;
                    }
                    if (!kwList.isEmpty()) builder.keywords(kwList);
                } catch (Exception e) {
                    logger.warn("提取关键词失败: {}", e.getMessage());
                }
            }
            // 高校名称
            if (result.getUniversityName() != null && !result.getUniversityName().isEmpty()) {
                builder.universityName(result.getUniversityName());
            }
            // 主题分类
            if (result.getTopicCategory() != null) {
                builder.topicCategory(result.getTopicCategory());
            }
        }
        
        return builder.build();
    }
    
    @Override
    @Transactional
    public AnalysisTaskVO createUrlImportTask(String url, String title, String taskType, String userId, String folderId) {
        // 确定任务类型
        if (taskType == null || taskType.isEmpty()) {
            taskType = AnalysisTask.TaskType.FULL_ANALYSIS.name();
        } else {
            try {
                AnalysisTask.TaskType.valueOf(taskType);
            } catch (IllegalArgumentException e) {
                throw new BusinessException("无效的任务类型: " + taskType);
            }
        }
        
        // 自动生成标题
        if (title == null || title.isEmpty()) {
            title = "链接导入 - " + LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        
        // 创建视频占位记录（DOWNLOADING 状态）
        Video video = Video.builder()
                .id(IdUtil.fastSimpleUUID())
                .userId(userId)
                .title(title)
                .fileName("downloading...")
                .filePath("")
                .fileSize(0L)
                .status(Video.Status.DOWNLOADING.name())
                .sourceType(Video.SourceType.URL_IMPORT.name())
                .sourceUrl(url)
                .folderId(folderId != null && !folderId.isEmpty() ? folderId : null)
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        videoMapper.insert(video);
        
        // 创建分析任务（DOWNLOADING 状态）
        AnalysisTask task = AnalysisTask.builder()
                .id(IdUtil.fastSimpleUUID())
                .videoId(video.getId())
                .userId(userId)
                .taskType(taskType)
                .status(AnalysisTask.Status.DOWNLOADING.name())
                .progress(0)
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        analysisTaskMapper.insert(task);
        
        // 增加用户累计分析次数
        userMapper.incrementAnalysisCount(userId);
        
        // 清除缓存
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_LIST + userId + ":*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.VIDEO_LIST + userId + ":*");
        
        logger.info("创建URL导入任务: taskId={}, videoId={}, url={}", task.getId(), video.getId(), url);
        
        return convertToVO(task, video, null);
    }
    
    @Override
    @Transactional
    public void markTaskPending(String taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) return;
        
        task.setStatus(AnalysisTask.Status.PENDING.name());
        task.setGmtModified(LocalDateTime.now());
        analysisTaskMapper.updateById(task);
        
        // 更新视频状态为 ANALYZING
        videoService.updateVideoStatus(task.getVideoId(), Video.Status.ANALYZING.name());
        
        // 清除缓存
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_BY_ID + taskId + ":*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_LIST + task.getUserId() + ":*");
        
        logger.info("任务已转为PENDING，等待分析: taskId={}", taskId);
    }
    
    @Override
    public String getTaskStatus(String taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        return task != null ? task.getStatus() : null;
    }
    
    @Override
    public void updateTaskProgress(String taskId, int progress, String message) {
        try {
            AnalysisTask task = analysisTaskMapper.selectById(taskId);
            if (task != null) {
                task.setProgress(progress);
                task.setGmtModified(LocalDateTime.now());
                analysisTaskMapper.updateById(task);
                
                // 通过 WebSocket 推送进度
                TaskProgressWebSocket.sendTaskProgress(task.getUserId(), taskId, task.getVideoId(),
                        task.getStatus(), progress, message);
            }
        } catch (Exception e) {
            logger.warn("更新任务进度失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }
    
    /**
     * 向 Python 发送取消信号，物理停止正在运行的分析进程
     * Python 消费 algorithm.cancel.queue 后立即中断当前 taskId 的处理
     */
    private void sendCancelSignal(String taskId) {
        try {
            Map<String, String> cancelMsg = new java.util.HashMap<>();
            cancelMsg.put("taskId", taskId);
            cancelMsg.put("action", "CANCEL");
            rabbitTemplate.convertAndSend(RabbitMQConfig.ALGORITHM_CANCEL_QUEUE,
                    com.alibaba.fastjson2.JSON.toJSONString(cancelMsg));
            logger.info("已向 Python 发送取消信号 [Process destroyed]: taskId={}", taskId);
        } catch (Exception e) {
            logger.warn("发送取消信号失败（不影响数据库状态）: taskId={}, error={}", taskId, e.getMessage());
        }
    }
}

