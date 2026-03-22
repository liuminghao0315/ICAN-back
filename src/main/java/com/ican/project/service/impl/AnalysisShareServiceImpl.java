package com.ican.project.service.impl;

import cn.hutool.core.util.IdUtil;
import com.ican.project.exception.BusinessException;
import com.ican.project.model.common.Constants;
import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.model.vo.AnalysisShareVO;
import com.ican.project.service.AnalysisResultService;
import com.ican.project.service.AnalysisShareService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class AnalysisShareServiceImpl implements AnalysisShareService {

    private final AnalysisResultService analysisResultService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final long shareExpireDays;

    public AnalysisShareServiceImpl(
            AnalysisResultService analysisResultService,
            RedisTemplate<String, Object> redisTemplate,
            @Value("${analysis-share-expire-days:7}") long shareExpireDays
    ) {
        this.analysisResultService = analysisResultService;
        this.redisTemplate = redisTemplate;
        this.shareExpireDays = shareExpireDays;
    }

    @Override
    public AnalysisShareVO createShare(String resultId, String userId) {
        if (resultId == null || resultId.isBlank()) {
            throw new BusinessException("分析结果ID不能为空");
        }

        // 复用现有权限校验：只有结果拥有者或管理员才能创建分享
        analysisResultService.getResultById(resultId, userId);

        String token = IdUtil.fastSimpleUUID();
        redisTemplate.opsForValue().set(
                Constants.RedisKey.ANALYSIS_SHARE_PREFIX + token,
                resultId,
                shareExpireDays,
                TimeUnit.DAYS
        );

        return AnalysisShareVO.builder()
                .token(token)
                .resultId(resultId)
                .sharePath("/share/analysis/" + token)
                .expireAt(LocalDateTime.now().plusDays(shareExpireDays))
                .build();
    }

    @Override
    public AnalysisResultVO getSharedResult(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("分享链接不存在或已失效");
        }

        Object resultIdValue = redisTemplate.opsForValue().get(Constants.RedisKey.ANALYSIS_SHARE_PREFIX + token);
        if (resultIdValue == null) {
            throw new BusinessException("分享链接不存在或已失效");
        }

        String resultId = String.valueOf(resultIdValue);
        return analysisResultService.getResultByIdForShare(resultId);
    }
}