package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.AnalysisResultMapper;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.mapper.TaskWordPackMapper;
import com.ican.project.mapper.UserMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.model.vo.WordPackVO;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.MinioService;
import com.ican.project.service.WordPackService;
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
    private TaskWordPackMapper taskWordPackMapper;
    
    @Autowired
    private WordPackService wordPackService;
    
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
        
        // 查询分析结果（新数据结构不再有riskLevel字段，暂时忽略筛选）
        Page<AnalysisResult> resultPage = new Page<>(page, size);
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AnalysisResult::getVideoId, videoIds);
        
        // TODO: 新数据结构需要重新设计风险等级筛选逻辑
        // if (riskLevel != null && !riskLevel.isEmpty()) {
        //     wrapper.eq(AnalysisResult::getRiskLevel, riskLevel.toUpperCase());
        // }
        
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
        
        // TODO: 新数据结构需要重新设计统计逻辑
        // 基于 opinionRiskFusion.finalScore 计算风险统计
        long highRiskCount = 0L, mediumRiskCount = 0L, lowRiskCount = 0L;
        long positiveSentimentCount = 0L, negativeSentimentCount = 0L, neutralSentimentCount = 0L;
        double totalRiskScore = 0.0;
        int validScoreCount = 0;
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (AnalysisResult r : results) {
            // 风险统计
            String fusion = r.getOpinionRiskFusion();
            if (fusion != null && !fusion.isBlank()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(fusion);
                    com.fasterxml.jackson.databind.JsonNode scoreNode = node.get("finalScore");
                    if (scoreNode != null && !scoreNode.isNull()) {
                        double score = scoreNode.asDouble();
                        totalRiskScore += score;
                        validScoreCount++;
                        if (score >= 67) highRiskCount++;
                        else if (score >= 34) mediumRiskCount++;
                        else lowRiskCount++;
                    } else {
                        lowRiskCount++;
                    }
                } catch (Exception e) {
                    lowRiskCount++;
                }
            } else {
                lowRiskCount++;
            }
            // 情感统计（从 attitudeEvidences 统计）
            String attitudeEvidences = r.getAttitudeEvidences();
            if (attitudeEvidences != null && !attitudeEvidences.isBlank()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode evArr = objectMapper.readTree(attitudeEvidences);
                    int pos = 0, neu = 0, neg = 0;
                    for (com.fasterxml.jackson.databind.JsonNode ev : evArr) {
                        com.fasterxml.jackson.databind.JsonNode ss = ev.get("sentimentScore");
                        if (ss != null && !ss.isNull()) {
                            int s = ss.asInt();
                            if (s < 33) pos++;
                            else if (s > 67) neg++;
                            else neu++;
                        }
                    }
                    if (neg >= pos && neg >= neu) negativeSentimentCount++;
                    else if (pos >= neu) positiveSentimentCount++;
                    else neutralSentimentCount++;
                } catch (Exception e) {
                    neutralSentimentCount++;
                }
            } else {
                neutralSentimentCount++;
            }
        }
        stats.put("avgRiskScore", validScoreCount > 0 ? totalRiskScore / validScoreCount : 0.0);
        stats.put("highRiskCount", highRiskCount);
        stats.put("mediumRiskCount", mediumRiskCount);
        stats.put("lowRiskCount", lowRiskCount);
        stats.put("positiveSentimentCount", positiveSentimentCount);
        stats.put("negativeSentimentCount", negativeSentimentCount);
        stats.put("neutralSentimentCount", neutralSentimentCount);
        
        // 高校相关内容统计（基于universityName是否为空）
        long universityRelatedCount = results.stream()
                .filter(r -> r.getUniversityName() != null && !r.getUniversityName().isEmpty())
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
        
        LambdaQueryWrapper<AnalysisResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.in(AnalysisResult::getVideoId, videoIds);
        List<AnalysisResult> results = analysisResultMapper.selectList(resultWrapper);
        
        // 基于 opinionRiskFusion.finalScore 计算风险等级分布
        // HIGH: finalScore >= 67, MEDIUM: 34-66, LOW: < 34
        long highCount = 0L, mediumCount = 0L, lowCount = 0L;
        for (AnalysisResult r : results) {
            String fusion = r.getOpinionRiskFusion();
            if (fusion == null || fusion.isBlank()) {
                lowCount++;
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(fusion);
                com.fasterxml.jackson.databind.JsonNode scoreNode = node.get("finalScore");
                if (scoreNode == null || scoreNode.isNull()) {
                    lowCount++;
                    continue;
                }
                double score = scoreNode.asDouble();
                if (score >= 67) {
                    highCount++;
                } else if (score >= 34) {
                    mediumCount++;
                } else {
                    lowCount++;
                }
            } catch (Exception e) {
                lowCount++;
            }
        }
        distribution.put("HIGH", highCount);
        distribution.put("MEDIUM", mediumCount);
        distribution.put("LOW", lowCount);
        
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
     * 转换为VO（全新实现，适配新前端数据结构）
     */
    private AnalysisResultVO convertToVO(AnalysisResult result, Video video) {
        try {
            logger.info("开始转换AnalysisResult到VO: resultId={}", result.getId());
            
            // ========== 1. 构建视频基本信息 ==========
            logger.debug("构建VideoInfo...");
            AnalysisResultVO.VideoInfo videoInfo = AnalysisResultVO.VideoInfo.builder()
                    .videoId(result.getVideoId())
                    .videoUrl(result.getVideoUrl() != null ? result.getVideoUrl() : 
                            (video != null ? minioService.getFileUrl(video.getFilePath()) : ""))
                    .fileName(video != null ? video.getFileName() : "")
                    .duration(video != null && video.getDuration() != null ? video.getDuration() : 0.0)
                    .uploadSource(video != null ? "本地上传" : "")
                    .description(result.getAiDescription())
                    .detectedKeywords(parseJsonList(result.getDetectedKeywords()))
                    .mainCharacter(parseJsonMap(result.getMainCharacter()))
                    .build();
            
            // ========== 2. 核心分析维度 ==========
            logger.debug("构建Identity...");
            AnalysisResultVO.IdentityAnalysis identity = AnalysisResultVO.IdentityAnalysis.builder()
                    .identityLabel(result.getIdentityLabel())
                    .evidences(parseJsonList(result.getIdentityEvidences()))
                    .modalityFusion(parseJsonMap(result.getIdentityFusion()))
                    .build();
            
            logger.debug("构建University...");
            AnalysisResultVO.UniversityAnalysis university = AnalysisResultVO.UniversityAnalysis.builder()
                    .universityName(result.getUniversityName())
                    .evidences(parseJsonList(result.getUniversityEvidences()))
                    .modalityFusion(parseJsonMap(result.getUniversityFusion()))
                    .build();
            
            logger.debug("构建Topic...");
            AnalysisResultVO.TopicAnalysis topic = AnalysisResultVO.TopicAnalysis.builder()
                    .topicCategory(result.getTopicCategory())
                    .topicSubCategory(result.getTopicSubCategory())
                    .evidences(parseJsonList(result.getTopicEvidences()))
                    .modalityFusion(parseJsonMap(result.getTopicFusion()))
                    .build();
            
            logger.debug("构建Attitude...");
            AnalysisResultVO.AttitudeAnalysis attitude = AnalysisResultVO.AttitudeAnalysis.builder()
                    .evidences(parseJsonList(result.getAttitudeEvidences()))
                    .build();
            
            logger.debug("构建OpinionRisk...");
            AnalysisResultVO.OpinionRiskAnalysis opinionRisk = AnalysisResultVO.OpinionRiskAnalysis.builder()
                    .riskReason(result.getOpinionRiskReason())
                    .evidences(parseJsonList(result.getOpinionRiskEvidences()))
                    .modalityFusion(parseJsonMap(result.getOpinionRiskFusion()))
                    .build();
            
            logger.debug("构建Action...");
            AnalysisResultVO.ActionSuggestion action = AnalysisResultVO.ActionSuggestion.builder()
                    .actionSuggestion(result.getActionSuggestion())
                    .actionDetail(result.getActionDetail())
                    .evidences(parseJsonList(result.getActionEvidences()))
                    .modalityFusion(parseJsonMap(result.getActionFusion()))
                    .build();
            
            // ========== 3. 时间轴数据 ==========
            logger.debug("构建TimelineData...");
            AnalysisResultVO.TimelineData timelineData = AnalysisResultVO.TimelineData.builder()
                    .timeGranularity(result.getTimeGranularity())
                    .videoRisks(parseJsonList(result.getVideoRisks()))
                    .audioEmotions(parseJsonList(result.getAudioEmotions()))
                    .textRisks(parseJsonList(result.getTextRisks()))
                    .comprehensiveRisks(parseJsonList(result.getComprehensiveRisks()))
                    .radarByTime(parseJsonList(result.getRadarByTime()))
                    .averageRadarData(parseJsonIntList(result.getAverageRadarData()))
                    .build();
            
            // ========== 4. 全模态智能事件流 ==========
            logger.debug("解析TimelineEvents...");
            List<Object> timelineEvents = parseJsonList(result.getTimelineEvents());
            
            // ========== 5. 场景识别 ==========
            logger.debug("解析SceneRecognition...");
            List<Object> sceneRecognition = parseJsonList(result.getSceneRecognition());
            
            // ========== 6. 构建完整VO ==========
            logger.debug("构建最终VO...");
            
            // 计算isUniversityRelated（根据detectedKeywords判断）
            Boolean isUniversityRelated = false;
            List<Object> detectedKeywords = parseJsonList(result.getDetectedKeywords());
            if (detectedKeywords != null && !detectedKeywords.isEmpty()) {
                for (Object kwObj : detectedKeywords) {
                    if (kwObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> kwMap = (Map<String, Object>) kwObj;
                        Object isUnivRelated = kwMap.get("isUniversityRelated");
                        if (isUnivRelated != null && Boolean.TRUE.equals(isUnivRelated)) {
                            isUniversityRelated = true;
                            break;
                        }
                    }
                }
            }
            
            AnalysisResultVO vo = AnalysisResultVO.builder()
                    .id(result.getId())
                    .taskId(result.getTaskId())
                    .isUniversityRelated(isUniversityRelated)
                    .videoInfo(videoInfo)
                    .identity(identity)
                    .university(university)
                    .topic(topic)
                    .attitude(attitude)
                    .opinionRisk(opinionRisk)
                    .action(action)
                    .timelineData(timelineData)
                    .timelineEvents(timelineEvents)
                    .sceneRecognition(sceneRecognition)
                    .gmtCreated(result.getGmtCreated())
                    .build();
            
            // 填充任务关联的词库包
            if (result.getTaskId() != null) {
                try {
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ican.project.model.entity.TaskWordPack> twpWrapper =
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                    twpWrapper.eq(com.ican.project.model.entity.TaskWordPack::getTaskId, result.getTaskId());
                    List<com.ican.project.model.entity.TaskWordPack> taskWordPacks = taskWordPackMapper.selectList(twpWrapper);
                    if (taskWordPacks != null && !taskWordPacks.isEmpty()) {
                        List<String> packIds = taskWordPacks.stream()
                                .map(com.ican.project.model.entity.TaskWordPack::getPackId)
                                .collect(java.util.stream.Collectors.toList());
                        List<WordPackVO> packs = wordPackService.getPacksWithWords(packIds);
                        vo.setWordPacks(packs);
                    }
                } catch (Exception e) {
                    logger.warn("获取任务词库包失败: taskId={}, error={}", result.getTaskId(), e.getMessage());
                }
            }
            
            logger.info("AnalysisResult转换VO成功: resultId={}", result.getId());
            return vo;
            
        } catch (Exception e) {
            logger.error("转换AnalysisResult到VO失败: resultId={}, error={}", result.getId(), e.getMessage(), e);
            throw new BusinessException("数据转换失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析JSON字符串为List
     */
    private List<Object> parseJsonList(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            return JSON.parseObject(jsonStr, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            logger.warn("解析JSON List失败: {}", e.getMessage());
            return new java.util.ArrayList<>();
        }
    }
    
    /**
     * 解析JSON字符串为整数List
     */
    private List<Integer> parseJsonIntList(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            return JSON.parseObject(jsonStr, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            logger.warn("解析JSON Int List失败: {}", e.getMessage());
            return new java.util.ArrayList<>();
        }
    }
    
    /**
     * 解析JSON字符串为Map
     */
    private Map<String, Object> parseJsonMap(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return new java.util.HashMap<>();
        }
        try {
            return JSON.parseObject(jsonStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("解析JSON Map失败: {}", e.getMessage());
            return new java.util.HashMap<>();
        }
    }
}

