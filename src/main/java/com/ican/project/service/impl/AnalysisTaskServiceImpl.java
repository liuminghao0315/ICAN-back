package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.ican.project.service.MinioService;
import com.ican.project.service.VideoService;
import com.ican.project.utils.RedisCacheUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private MinioService minioService;
    
    @Autowired
    private RedisCacheUtil redisCacheUtil;
    
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
        
        // 检查是否有正在处理的任务
        LambdaQueryWrapper<AnalysisTask> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(AnalysisTask::getVideoId, dto.getVideoId())
                .in(AnalysisTask::getStatus, 
                    AnalysisTask.Status.PENDING.name(), 
                    AnalysisTask.Status.PROCESSING.name());
        Long existingCount = analysisTaskMapper.selectCount(existingWrapper);
        if (existingCount > 0) {
            // 如果是强制重新分析，取消已有任务
            if (Boolean.TRUE.equals(dto.getForceRestart())) {
                LambdaUpdateWrapper<AnalysisTask> cancelWrapper = new LambdaUpdateWrapper<>();
                cancelWrapper.eq(AnalysisTask::getVideoId, dto.getVideoId())
                        .in(AnalysisTask::getStatus, 
                            AnalysisTask.Status.PENDING.name(), 
                            AnalysisTask.Status.PROCESSING.name())
                        .set(AnalysisTask::getStatus, AnalysisTask.Status.CANCELLED.name())
                        .set(AnalysisTask::getErrorMessage, "用户手动取消，重新分析")
                        .set(AnalysisTask::getGmtModified, LocalDateTime.now());
                analysisTaskMapper.update(null, cancelWrapper);
                logger.info("强制重新分析，已取消视频 {} 的现有任务", dto.getVideoId());
            } else {
                throw new BusinessException("该视频已有正在处理的分析任务");
            }
        }
        
        // 确定任务类型
        String taskType = dto.getTaskType();
        if (taskType == null || taskType.isEmpty()) {
            taskType = AnalysisTask.TaskType.FULL_ANALYSIS.name();
        } else {
            // 验证任务类型有效
            try {
                AnalysisTask.TaskType.valueOf(taskType);
            } catch (IllegalArgumentException e) {
                throw new BusinessException("无效的任务类型: " + taskType);
            }
        }
        
        // 创建任务
        AnalysisTask task = AnalysisTask.builder()
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
        
        // 清除相关缓存
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.TASK_LIST + userId + ":*");
        
        // 更新视频状态为分析中
        videoService.updateVideoStatus(dto.getVideoId(), Video.Status.ANALYZING.name());
        
        // 增加用户累计分析次数（只增不减）
        userMapper.incrementAnalysisCount(userId);
        
        logger.info("创建分析任务成功: taskId={}, videoId={}, taskType={}", 
                task.getId(), dto.getVideoId(), taskType);
        
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
        // 使用 JOIN 查询，在数据库层面进行全局排序和筛选
        String effectiveStatus = (status != null && !status.isEmpty()) ? status.toUpperCase() : null;
        String effectiveRiskLevel = (riskLevel != null && !riskLevel.isEmpty()) ? riskLevel.toUpperCase() : null;
        String effectiveSortBy = sortBy != null ? sortBy : "gmtCreated";
        String effectiveSortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        
        // 直接查询数据库，不使用缓存，确保数据准确性（特别是 total 总数）
        Page<java.util.Map<String, Object>> taskPage = new Page<>(page, size);
        
        Page<java.util.Map<String, Object>> result = analysisTaskMapper.selectTasksWithJoin(
                taskPage, userId, effectiveStatus, effectiveRiskLevel, effectiveSortBy, effectiveSortOrder);
        
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
        
        // 分析结果信息
        String resultId = (String) map.get("result_id");
        if (resultId != null) {
            builder.hasResult(true)
                    .resultId(resultId);
            if (map.get("risk_score") != null) {
                builder.riskScore(((Number) map.get("risk_score")).doubleValue());
            }
            builder.riskLevel((String) map.get("risk_level"))
                    .sentimentLabel((String) map.get("sentiment_label"));
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
        
        // 更新视频状态
        videoService.updateVideoStatus(task.getVideoId(), Video.Status.UPLOADED.name());
        
        logger.info("取消分析任务: taskId={}", taskId);
    }
    
    @Override
    @Transactional
    public AnalysisTaskVO retryTask(String taskId, String userId) {
        AnalysisTask oldTask = analysisTaskMapper.selectById(taskId);
        if (oldTask == null) {
            throw new BusinessException("任务不存在");
        }
        if (oldTask.getUserId() == null || !oldTask.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该任务");
        }
        
        // 只能重试失败或已取消的任务
        String currentStatus = oldTask.getStatus();
        if (!AnalysisTask.Status.FAILED.name().equals(currentStatus) 
                && !AnalysisTask.Status.CANCELLED.name().equals(currentStatus)) {
            throw new BusinessException("只能重试失败或已取消的任务");
        }
        
        // 创建新任务
        AnalysisTaskDTO dto = AnalysisTaskDTO.builder()
                .videoId(oldTask.getVideoId())
                .taskType(oldTask.getTaskType())
                .build();
        
        return createTask(dto, userId);
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
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            logger.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .set(AnalysisTask::getStatus, AnalysisTask.Status.FAILED.name())
                .set(AnalysisTask::getErrorMessage, errorMessage)
                .set(AnalysisTask::getCompletedAt, LocalDateTime.now())
                .set(AnalysisTask::getGmtModified, LocalDateTime.now());
        
        analysisTaskMapper.update(null, wrapper);
        
        // 更新视频状态
        videoService.updateVideoStatus(task.getVideoId(), Video.Status.FAILED.name());
        
        logger.error("任务失败: taskId={}, error={}", taskId, errorMessage);
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
                    .resultId(result.getId())
                    .riskScore(result.getRiskScore() != null ? result.getRiskScore().doubleValue() : null)
                    .riskLevel(result.getRiskLevel())
                    .sentimentLabel(result.getSentimentLabel());
        } else {
            builder.hasResult(false);
        }
        
        return builder.build();
    }
}

