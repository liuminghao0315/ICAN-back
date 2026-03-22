package com.ican.project.service.impl;

import com.ican.project.exception.BusinessException;
import com.ican.project.model.common.Constants;
import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.model.vo.AnalysisShareVO;
import com.ican.project.service.AnalysisResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisShareServiceImplTest {

    @Mock
    private AnalysisResultService analysisResultService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private AnalysisShareServiceImpl analysisShareService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        analysisShareService = new AnalysisShareServiceImpl(analysisResultService, redisTemplate, 7);
    }

    @Test
    void shouldCreateShareTokenAfterValidatingResultOwnership() {
        AnalysisResultVO resultVO = new AnalysisResultVO();
        resultVO.setId("result-1");
        when(analysisResultService.getResultById("result-1", "user-1")).thenReturn(resultVO);

        AnalysisShareVO shareVO = analysisShareService.createShare("result-1", "user-1");

        assertNotNull(shareVO);
        assertNotNull(shareVO.getToken());
        assertEquals("result-1", shareVO.getResultId());
        assertNotNull(shareVO.getExpireAt());
        verify(valueOperations).set(
                eq(Constants.RedisKey.ANALYSIS_SHARE_PREFIX + shareVO.getToken()),
                eq("result-1"),
                eq(7L),
                eq(TimeUnit.DAYS)
        );
    }

    @Test
    void shouldReturnSharedResultWhenTokenExists() {
        AnalysisResultVO resultVO = new AnalysisResultVO();
        resultVO.setId("result-2");
        resultVO.setGmtCreated(LocalDateTime.now());

        when(valueOperations.get(Constants.RedisKey.ANALYSIS_SHARE_PREFIX + "share-token")).thenReturn("result-2");
        when(analysisResultService.getResultByIdForShare("result-2")).thenReturn(resultVO);

        AnalysisResultVO sharedResult = analysisShareService.getSharedResult("share-token");

        assertEquals("result-2", sharedResult.getId());
    }

    @Test
    void shouldRejectInvalidOrExpiredShareToken() {
        when(valueOperations.get(Constants.RedisKey.ANALYSIS_SHARE_PREFIX + "expired-token")).thenReturn(null);

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> analysisShareService.getSharedResult("expired-token")
        );

        assertEquals("分享链接不存在或已失效", ex.getMessage());
    }
}