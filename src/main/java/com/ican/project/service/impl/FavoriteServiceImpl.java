package com.ican.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.AnalysisResultMapper;
import com.ican.project.mapper.AnalysisTaskMapper;
import com.ican.project.mapper.UserFavoriteMapper;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.AnalysisTask;
import com.ican.project.model.entity.UserFavorite;
import com.ican.project.model.vo.AnalysisTaskVO;
import com.ican.project.service.FavoriteService;
import com.ican.project.service.MinioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户收藏服务实现
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    private static final Logger logger = LoggerFactory.getLogger(FavoriteServiceImpl.class);

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;

    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;

    @Autowired
    private AnalysisResultMapper analysisResultMapper;

    @Autowired
    private MinioService minioService;

    // ──────────────────────────────────────────────────────────────────────────
    // 收藏 / 取消收藏
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void addFavorite(String userId, String taskId) {
        // 校验任务存在且已完成
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (!"COMPLETED".equals(task.getStatus())) {
            throw new BusinessException("只有已完成的分析任务才能收藏");
        }

        UserFavorite favorite = UserFavorite.builder()
                .userId(userId)
                .taskId(taskId)
                .gmtCreated(LocalDateTime.now())
                .build();

        try {
            userFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 联合唯一索引兜底：已收藏则静默忽略，幂等处理
            logger.debug("用户 {} 已收藏任务 {}，忽略重复收藏", userId, taskId);
        }
    }

    @Override
    @Transactional
    public void removeFavorite(String userId, String taskId) {
        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFavorite::getUserId, userId)
               .eq(UserFavorite::getTaskId, taskId);
        userFavoriteMapper.delete(wrapper);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 收藏列表查询
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public Page<AnalysisTaskVO> getFavoriteList(String userId, int page, int size,
                                                 String riskLevel, String keyword,
                                                 String sourceType,
                                                 String sortField, String sortDir) {
        String effectiveRiskLevel  = (riskLevel   != null && !riskLevel.isEmpty())   ? riskLevel.toUpperCase() : null;
        String effectiveKeyword    = (keyword     != null && !keyword.isEmpty())     ? keyword     : null;
        String effectiveSourceType = (sourceType  != null && !sourceType.isEmpty())  ? sourceType  : null;
        // 默认按收藏时间倒序
        String effectiveSortField = "riskScore".equals(sortField) ? "riskScore" : "gmtCreated";
        String effectiveSortDir   = "asc".equalsIgnoreCase(sortDir) ? "asc" : "desc";

        Page<Map<String, Object>> rawPage = new Page<>(page, size);
        Page<Map<String, Object>> result = userFavoriteMapper.selectFavoritesWithJoin(
                rawPage, userId, effectiveRiskLevel, effectiveKeyword, effectiveSourceType, effectiveSortField, effectiveSortDir);

        List<AnalysisTaskVO> voList = result.getRecords().stream()
                .map(this::convertMapToVO)
                .collect(Collectors.toList());

        Page<AnalysisTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 内部：Map → AnalysisTaskVO（复用与 AnalysisTaskServiceImpl 相同的转换逻辑）
    // ──────────────────────────────────────────────────────────────────────────

    private AnalysisTaskVO convertMapToVO(Map<String, Object> map) {
        AnalysisTaskVO.AnalysisTaskVOBuilder builder = AnalysisTaskVO.builder()
                .id((String) map.get("id"))
                .videoId((String) map.get("video_id"))
                .taskType((String) map.get("task_type"))
                .status((String) map.get("status"))
                .progress(map.get("progress") != null ? ((Number) map.get("progress")).intValue() : 0)
                .errorMessage((String) map.get("error_message"))
                .failureType((String) map.get("failure_type"))
                // 收藏的任务一定是 COMPLETED，标记已收藏
                .isFavorited(true);

        // 时间字段
        if (map.get("started_at") != null)   builder.startedAt((LocalDateTime) map.get("started_at"));
        if (map.get("completed_at") != null) builder.completedAt((LocalDateTime) map.get("completed_at"));
        if (map.get("gmt_created") != null)  builder.gmtCreated((LocalDateTime) map.get("gmt_created"));

        // 视频信息
        String videoTitle = (String) map.get("video_title");
        if (videoTitle != null) builder.videoTitle(videoTitle);

        if (map.get("video_duration") != null)
            builder.videoDuration(((Number) map.get("video_duration")).doubleValue());

        String videoFilePath = (String) map.get("video_file_path");
        if (videoFilePath != null) builder.videoUrl(minioService.getFileUrl(videoFilePath));

        String videoThumbnailPath = (String) map.get("video_thumbnail_path");
        if (videoThumbnailPath != null && !videoThumbnailPath.isEmpty())
            builder.thumbnailUrl(minioService.getFileUrl(videoThumbnailPath));

        // 来源信息
        String videoSourceType = (String) map.get("video_source_type");
        if (videoSourceType != null) builder.sourceType(videoSourceType);
        String videoSourceUrl = (String) map.get("video_source_url");
        if (videoSourceUrl != null) builder.sourceUrl(videoSourceUrl);

        // 文件夹归属
        String videoFolderId = (String) map.get("video_folder_id");
        if (videoFolderId != null) builder.folderId(videoFolderId);
        String videoFolderName = (String) map.get("video_folder_name");
        if (videoFolderName != null) builder.folderName(videoFolderName);

        // 分析结果摘要
        String resultId = (String) map.get("result_id");
        if (resultId != null) {
            builder.hasResult(true).resultId(resultId);
            try {
                AnalysisResult result = analysisResultMapper.selectById(resultId);
                if (result != null) {
                    // 风险等级
                    String opinionRiskFusion = result.getOpinionRiskFusion();
                    if (opinionRiskFusion != null && !opinionRiskFusion.isEmpty()) {
                        com.alibaba.fastjson2.JSONObject fusion =
                                com.alibaba.fastjson2.JSON.parseObject(opinionRiskFusion);
                        Integer finalScore = fusion.getInteger("finalScore");
                        if (finalScore != null) {
                            builder.riskScore(finalScore / 100.0);
                            if (finalScore >= 67)      builder.riskLevel("HIGH");
                            else if (finalScore >= 34) builder.riskLevel("MEDIUM");
                            else                       builder.riskLevel("LOW");
                        }
                    }
                    // 情感标签
                    String attitudeEvidences = result.getAttitudeEvidences();
                    if (attitudeEvidences != null && !attitudeEvidences.isEmpty()) {
                        com.alibaba.fastjson2.JSONArray evidences =
                                com.alibaba.fastjson2.JSON.parseArray(attitudeEvidences);
                        if (evidences != null && !evidences.isEmpty()) {
                            int positive = 0, neutral = 0, negative = 0;
                            for (Object obj : evidences) {
                                if (obj instanceof com.alibaba.fastjson2.JSONObject) {
                                    Integer s = ((com.alibaba.fastjson2.JSONObject) obj).getInteger("sentimentScore");
                                    if (s != null) {
                                        if (s < 33) positive++;
                                        else if (s > 67) negative++;
                                        else neutral++;
                                    }
                                }
                            }
                            if (negative >= positive && negative >= neutral) builder.sentimentLabel("NEGATIVE");
                            else if (positive >= neutral)                    builder.sentimentLabel("POSITIVE");
                            else                                             builder.sentimentLabel("NEUTRAL");
                        }
                    }
                    // 关键词
                    String detectedKeywords = result.getDetectedKeywords();
                    if (detectedKeywords != null && !detectedKeywords.isEmpty()) {
                        com.alibaba.fastjson2.JSONArray kwArr =
                                com.alibaba.fastjson2.JSON.parseArray(detectedKeywords);
                        List<String> kwList = new ArrayList<>();
                        for (Object obj : kwArr) {
                            if (obj instanceof com.alibaba.fastjson2.JSONObject) {
                                String word = ((com.alibaba.fastjson2.JSONObject) obj).getString("word");
                                if (word != null && !word.isEmpty()) kwList.add(word);
                            }
                            if (kwList.size() >= 5) break;
                        }
                        if (!kwList.isEmpty()) builder.keywords(kwList);
                    }
                    if (result.getUniversityName() != null && !result.getUniversityName().isEmpty())
                        builder.universityName(result.getUniversityName());
                    if (result.getTopicCategory() != null)
                        builder.topicCategory(result.getTopicCategory());
                }
            } catch (Exception e) {
                logger.warn("提取收藏任务分析结果摘要失败: resultId={}, error={}", resultId, e.getMessage());
            }
        } else {
            builder.hasResult(false);
        }

        return builder.build();
    }
}
