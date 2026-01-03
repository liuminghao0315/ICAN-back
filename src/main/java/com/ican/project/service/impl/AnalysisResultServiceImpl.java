package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.AnalysisResultMapper;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.mapper.UserMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.MinioService;
import com.ican.project.utils.RedisCacheUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分析结果服务实现
 */
@Service
public class AnalysisResultServiceImpl implements AnalysisResultService {
    
    private static final Logger logger = LoggerFactory.getLogger(AnalysisResultServiceImpl.class);
    
    @Autowired
    private AnalysisResultMapper analysisResultMapper;
    
    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;
    
    @Autowired
    private VideoMapper videoMapper;
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private MinioService minioService;
    
    @Autowired
    private RedisCacheUtil redisCacheUtil;
    
    @Override
    @Transactional
    public String saveResult(AnalysisResult result) {
        if (result.getId() == null || result.getId().isEmpty()) {
            result.setId(IdUtil.fastSimpleUUID());
        }
        if (result.getGmtCreated() == null) {
            result.setGmtCreated(LocalDateTime.now());
        }
        result.setGmtModified(LocalDateTime.now());
        
        analysisResultMapper.insert(result);
        
        // 清除相关缓存
        if (result.getVideoId() != null) {
            redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RESULT_BY_VIDEO_ID + result.getVideoId() + ":*");
        }
        if (result.getTaskId() != null) {
            // 查询任务获取userId，避免空指针异常
            AnalysisTask task = analysisTaskMapper.selectById(result.getTaskId());
            if (task != null && task.getUserId() != null) {
                redisCacheUtil.delete(RedisCacheUtil.CacheKey.RESULT_BY_TASK_ID + result.getTaskId() + ":" + task.getUserId());
            }
        }
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RESULT_LIST + "*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RESULT_STATS + "*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RISK_DISTRIBUTION + "*");
        
        logger.info("保存分析结果: resultId={}, taskId={}, videoId={}", 
                result.getId(), result.getTaskId(), result.getVideoId());
        
        return result.getId();
    }
    
    @Override
    public AnalysisResultVO getResultById(String resultId, String userId) {
        // 先从缓存获取
        String cacheKey = RedisCacheUtil.CacheKey.RESULT_BY_ID + resultId + ":" + userId;
        AnalysisResultVO cachedResult = redisCacheUtil.get(cacheKey, AnalysisResultVO.class);
        if (cachedResult != null) {
            logger.debug("从缓存获取分析结果: resultId={}", resultId);
            return cachedResult;
        }
        
        // 缓存未命中，查询数据库
        AnalysisResult result = analysisResultMapper.selectById(resultId);
        if (result == null) {
            throw new BusinessException("分析结果不存在");
        }
        
        // 验证权限（检查必要字段）
        if (result.getTaskId() == null) {
            throw new BusinessException("分析结果数据异常：任务ID为空");
        }
        AnalysisTask task = analysisTaskMapper.selectById(result.getTaskId());
        if (task == null || task.getUserId() == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该分析结果");
        }
        
        // 获取视频信息
        Video video = null;
        if (result.getVideoId() != null) {
            video = videoMapper.selectById(result.getVideoId());
        }
        AnalysisResultVO resultVO = convertToVO(result, video);
        
        // 存入缓存
        redisCacheUtil.set(cacheKey, resultVO);
        return resultVO;
    }
    
    @Override
    public AnalysisResultVO getResultByTaskId(String taskId, String userId) {
        // 先从缓存获取
        String cacheKey = RedisCacheUtil.CacheKey.RESULT_BY_TASK_ID + taskId + ":" + userId;
        AnalysisResultVO cachedResult = redisCacheUtil.get(cacheKey, AnalysisResultVO.class);
        if (cachedResult != null) {
            logger.debug("从缓存获取分析结果: taskId={}", taskId);
            return cachedResult;
        }
        
        // 验证任务权限
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (task.getUserId() == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该任务的分析结果");
        }
        
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisResult::getTaskId, taskId);
        AnalysisResult result = analysisResultMapper.selectOne(wrapper);
        
        if (result == null) {
            return null;
        }
        
        // 获取视频信息
        Video video = null;
        if (result.getVideoId() != null) {
            video = videoMapper.selectById(result.getVideoId());
        }
        AnalysisResultVO resultVO = convertToVO(result, video);
        
        // 存入缓存
        redisCacheUtil.set(cacheKey, resultVO);
        return resultVO;
    }
    
    @Override
    public AnalysisResultVO getLatestResultByVideoId(String videoId, String userId) {
        // 先从缓存获取
        String cacheKey = RedisCacheUtil.CacheKey.RESULT_BY_VIDEO_ID + videoId + ":" + userId;
        AnalysisResultVO cachedResult = redisCacheUtil.get(cacheKey, AnalysisResultVO.class);
        if (cachedResult != null) {
            logger.debug("从缓存获取最新分析结果: videoId={}", videoId);
            return cachedResult;
        }
        
        // 验证视频权限
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在");
        }
        if (video.getUserId() == null || !video.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该视频的分析结果");
        }
        
        // 获取最新结果
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisResult::getVideoId, videoId)
                .orderByDesc(AnalysisResult::getGmtCreated)
                .last("LIMIT 1");
        AnalysisResult result = analysisResultMapper.selectOne(wrapper);
        
        if (result == null) {
            return null;
        }
        
        AnalysisResultVO resultVO = convertToVO(result, video);
        
        // 存入缓存
        redisCacheUtil.set(cacheKey, resultVO);
        return resultVO;
    }
    
    @Override
    public Page<AnalysisResultVO> getUserResults(String userId, String riskLevel, int page, int size) {
        // 构建缓存键（仅第一页缓存）
        String cacheKey = RedisCacheUtil.CacheKey.RESULT_LIST + userId + ":" + 
                (riskLevel != null ? riskLevel : "") + ":" + page + ":" + size;
        
        // 先从缓存获取（仅第一页）
        if (page == 1) {
            @SuppressWarnings("unchecked")
            Page<AnalysisResultVO> cachedPage = redisCacheUtil.get(cacheKey, Page.class);
            if (cachedPage != null) {
                logger.debug("从缓存获取分析结果列表: userId={}, page={}", userId, page);
                return cachedPage;
            }
        }
        
        // 先获取用户的所有视频ID
        LambdaQueryWrapper<Video> videoWrapper = new LambdaQueryWrapper<>();
        videoWrapper.eq(Video::getUserId, userId)
                .select(Video::getId);
        List<Video> videos = videoMapper.selectList(videoWrapper);
        
        if (videos.isEmpty()) {
            Page<AnalysisResultVO> emptyPage = new Page<>(page, size, 0);
            // 空结果也缓存（仅第一页），避免重复查询
            if (page == 1) {
                redisCacheUtil.set(cacheKey, emptyPage, 10);
            }
            return emptyPage;
        }
        
        List<String> videoIds = videos.stream()
                .map(Video::getId)
                .collect(Collectors.toList());
        
        // 查询分析结果
        Page<AnalysisResult> resultPage = new Page<>(page, size);
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AnalysisResult::getVideoId, videoIds);
        
        if (riskLevel != null && !riskLevel.isEmpty()) {
            wrapper.eq(AnalysisResult::getRiskLevel, riskLevel.toUpperCase());
        }
        
        wrapper.orderByDesc(AnalysisResult::getGmtCreated);
        
        Page<AnalysisResult> resultList = analysisResultMapper.selectPage(resultPage, wrapper);
        
        // 构建视频Map便于查询
        Map<String, Video> videoMap = videos.stream()
                .collect(Collectors.toMap(Video::getId, v -> v));
        
        // 转换为VO
        Page<AnalysisResultVO> voPage = new Page<>(resultList.getCurrent(), resultList.getSize(), resultList.getTotal());
        voPage.setRecords(resultList.getRecords().stream()
                .map(result -> {
                    Video video = null;
                    if (result.getVideoId() != null) {
                        video = videoMap.get(result.getVideoId());
                        if (video == null) {
                            video = videoMapper.selectById(result.getVideoId());
                        }
                    }
                    return convertToVO(result, video);
                })
                .collect(Collectors.toList()));
        
        // 仅缓存第一页
        if (page == 1) {
            redisCacheUtil.set(cacheKey, voPage, 10); // 列表缓存10分钟
        }
        
        return voPage;
    }
    
    @Override
    public Map<String, Object> getUserAnalysisStats(String userId) {
        // 先从缓存获取
        String cacheKey = RedisCacheUtil.CacheKey.RESULT_STATS + userId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedStats = redisCacheUtil.get(cacheKey, Map.class);
        if (cachedStats != null) {
            logger.debug("从缓存获取分析统计: userId={}", userId);
            return cachedStats;
        }
        
        Map<String, Object> stats = new HashMap<>();
        
        // 获取用户的所有视频ID
        LambdaQueryWrapper<Video> videoWrapper = new LambdaQueryWrapper<>();
        videoWrapper.eq(Video::getUserId, userId);
        List<Video> videos = videoMapper.selectList(videoWrapper);
        
        // 获取累计分析次数（只增不减）
        Integer analysisCount = userMapper.getAnalysisCount(userId);
        stats.put("analysisCount", analysisCount != null ? analysisCount : 0);
        
        if (videos.isEmpty()) {
            stats.put("totalVideos", 0);
            stats.put("analyzedVideos", 0);
            stats.put("totalResults", 0);
            stats.put("avgRiskScore", 0.0);
            stats.put("highRiskCount", 0);
            stats.put("mediumRiskCount", 0);
            stats.put("lowRiskCount", 0);
            stats.put("positiveSentimentCount", 0);
            stats.put("negativeSentimentCount", 0);
            stats.put("neutralSentimentCount", 0);
            stats.put("universityRelatedCount", 0);
            // 空结果也存入缓存，避免重复查询
            redisCacheUtil.setStatsCache(cacheKey, stats);
            return stats;
        }
        
        List<String> videoIds = videos.stream()
                .map(Video::getId)
                .collect(Collectors.toList());
        
        // 查询分析结果
        LambdaQueryWrapper<AnalysisResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.in(AnalysisResult::getVideoId, videoIds);
        List<AnalysisResult> results = analysisResultMapper.selectList(resultWrapper);
        
        stats.put("totalVideos", videos.size());
        stats.put("totalResults", results.size());
        
        // 统计已分析的视频数量
        long analyzedCount = videos.stream()
                .filter(v -> Video.Status.COMPLETED.name().equals(v.getStatus()))
                .count();
        stats.put("analyzedVideos", analyzedCount);
        
        // 计算平均风险评分
        double avgRiskScore = results.stream()
                .filter(r -> r.getRiskScore() != null)
                .mapToDouble(r -> r.getRiskScore().doubleValue())
                .average()
                .orElse(0.0);
        stats.put("avgRiskScore", Math.round(avgRiskScore * 1000) / 1000.0);
        
        // 风险等级分布
        Map<String, Long> riskDistribution = getRiskDistribution(userId);
        stats.put("highRiskCount", riskDistribution.getOrDefault("HIGH", 0L));
        stats.put("mediumRiskCount", riskDistribution.getOrDefault("MEDIUM", 0L));
        stats.put("lowRiskCount", riskDistribution.getOrDefault("LOW", 0L));
        
        // 情感统计
        long positiveSentimentCount = results.stream()
                .filter(r -> "POSITIVE".equalsIgnoreCase(r.getSentimentLabel()))
                .count();
        long negativeSentimentCount = results.stream()
                .filter(r -> "NEGATIVE".equalsIgnoreCase(r.getSentimentLabel()))
                .count();
        long neutralSentimentCount = results.stream()
                .filter(r -> "NEUTRAL".equalsIgnoreCase(r.getSentimentLabel()))
                .count();
        stats.put("positiveSentimentCount", positiveSentimentCount);
        stats.put("negativeSentimentCount", negativeSentimentCount);
        stats.put("neutralSentimentCount", neutralSentimentCount);
        
        // 高校相关内容统计
        long universityRelatedCount = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsUniversityRelated()))
                .count();
        stats.put("universityRelatedCount", universityRelatedCount);
        
        // 存入缓存
        redisCacheUtil.setStatsCache(cacheKey, stats);
        
        return stats;
    }
    
    @Override
    public Map<String, Long> getRiskDistribution(String userId) {
        // 先从缓存获取
        String cacheKey = RedisCacheUtil.CacheKey.RISK_DISTRIBUTION + userId;
        @SuppressWarnings("unchecked")
        Map<String, Long> cachedDistribution = redisCacheUtil.get(cacheKey, Map.class);
        if (cachedDistribution != null) {
            logger.debug("从缓存获取风险分布: userId={}", userId);
            return cachedDistribution;
        }
        
        Map<String, Long> distribution = new HashMap<>();
        distribution.put("HIGH", 0L);
        distribution.put("MEDIUM", 0L);
        distribution.put("LOW", 0L);
        
        // 获取用户的所有视频ID
        LambdaQueryWrapper<Video> videoWrapper = new LambdaQueryWrapper<>();
        videoWrapper.eq(Video::getUserId, userId)
                .select(Video::getId);
        List<Video> videos = videoMapper.selectList(videoWrapper);
        
        if (videos.isEmpty()) {
            redisCacheUtil.setStatsCache(cacheKey, distribution);
            return distribution;
        }
        
        List<String> videoIds = videos.stream()
                .map(Video::getId)
                .collect(Collectors.toList());
        
        // 查询分析结果并统计
        LambdaQueryWrapper<AnalysisResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.in(AnalysisResult::getVideoId, videoIds)
                .isNotNull(AnalysisResult::getRiskLevel);
        List<AnalysisResult> results = analysisResultMapper.selectList(resultWrapper);
        
        for (AnalysisResult result : results) {
            String riskLevel = result.getRiskLevel();
            if (riskLevel != null) {
                distribution.merge(riskLevel.toUpperCase(), 1L, Long::sum);
            }
        }
        
        // 存入缓存
        redisCacheUtil.setStatsCache(cacheKey, distribution);
        
        return distribution;
    }
    
    @Override
    @Transactional
    public void deleteResult(String resultId, String userId) {
        AnalysisResult result = analysisResultMapper.selectById(resultId);
        if (result == null) {
            throw new BusinessException("分析结果不存在");
        }
        
        // 验证权限（检查必要字段）
        if (result.getTaskId() == null) {
            throw new BusinessException("分析结果数据异常：任务ID为空");
        }
        AnalysisTask task = analysisTaskMapper.selectById(result.getTaskId());
        if (task == null || task.getUserId() == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该分析结果");
        }
        
        analysisResultMapper.deleteById(resultId);
        
        // 清除相关缓存
        redisCacheUtil.delete(RedisCacheUtil.CacheKey.RESULT_BY_ID + resultId + ":" + userId);
        if (result.getTaskId() != null) {
            redisCacheUtil.delete(RedisCacheUtil.CacheKey.RESULT_BY_TASK_ID + result.getTaskId() + ":" + userId);
        }
        if (result.getVideoId() != null) {
            redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RESULT_BY_VIDEO_ID + result.getVideoId() + ":*");
        }
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RESULT_LIST + "*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RESULT_STATS + "*");
        redisCacheUtil.deleteByPattern(RedisCacheUtil.CacheKey.RISK_DISTRIBUTION + "*");
        
        logger.info("删除分析结果: resultId={}", resultId);
    }
    
    /**
     * 转换为VO
     */
    private AnalysisResultVO convertToVO(AnalysisResult result, Video video) {
        AnalysisResultVO.AnalysisResultVOBuilder builder = AnalysisResultVO.builder()
                .id(result.getId())
                .taskId(result.getTaskId())
                .videoId(result.getVideoId())
                .riskScore(result.getRiskScore())
                .riskLevel(result.getRiskLevel())
                .riskLevelDesc(AnalysisResultVO.getRiskLevelDescription(result.getRiskLevel()))
                .isUniversityRelated(result.getIsUniversityRelated())
                .universityName(result.getUniversityName())
                .universityConfidence(result.getUniversityConfidence())
                .topicCategory(result.getTopicCategory())
                .sentimentScore(result.getSentimentScore())
                .sentimentLabel(result.getSentimentLabel())
                .sentimentLabelDesc(AnalysisResultVO.getSentimentLabelDescription(result.getSentimentLabel()))
                .transcription(result.getTranscription())
                .spreadPotential(result.getSpreadPotential())
                .gmtCreated(result.getGmtCreated());
        
        // 解析JSON字段
        if (result.getTopicKeywords() != null) {
            try {
                List<String> keywords = JSON.parseObject(result.getTopicKeywords(), 
                        new TypeReference<List<String>>() {});
                builder.topicKeywords(keywords);
            } catch (Exception e) {
                logger.warn("解析主题关键词失败: {}", e.getMessage());
            }
        }
        
        if (result.getVideoFeatures() != null) {
            try {
                Map<String, Object> features = JSON.parseObject(result.getVideoFeatures(), 
                        new TypeReference<Map<String, Object>>() {});
                builder.videoFeatures(features);
            } catch (Exception e) {
                logger.warn("解析视频特征失败: {}", e.getMessage());
            }
        }
        
        if (result.getAudioFeatures() != null) {
            try {
                Map<String, Object> features = JSON.parseObject(result.getAudioFeatures(), 
                        new TypeReference<Map<String, Object>>() {});
                builder.audioFeatures(features);
            } catch (Exception e) {
                logger.warn("解析音频特征失败: {}", e.getMessage());
            }
        }
        
        if (result.getTextFeatures() != null) {
            try {
                Map<String, Object> features = JSON.parseObject(result.getTextFeatures(), 
                        new TypeReference<Map<String, Object>>() {});
                builder.textFeatures(features);
            } catch (Exception e) {
                logger.warn("解析文本特征失败: {}", e.getMessage());
            }
        }
        
        if (result.getAudienceAnalysis() != null) {
            try {
                Map<String, Object> analysis = JSON.parseObject(result.getAudienceAnalysis(), 
                        new TypeReference<Map<String, Object>>() {});
                builder.audienceAnalysis(analysis);
            } catch (Exception e) {
                logger.warn("解析受众分析失败: {}", e.getMessage());
            }
        }
        
        // 设置视频信息
        if (video != null) {
            builder.videoTitle(video.getTitle())
                    .videoDescription(video.getDescription())
                    .videoUrl(minioService.getFileUrl(video.getFilePath()));
        }
        
        return builder.build();
    }
}

