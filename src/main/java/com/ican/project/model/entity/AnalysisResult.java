package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分析结果实体类（全新数据结构，适配前端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("analysis_result")
public class AnalysisResult {
    
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    
    /**
     * 关联任务ID
     */
    private String taskId;
    
    /**
     * 关联视频ID
     */
    private String videoId;
    
    // ========== 视频基本信息 ==========
    
    /**
     * 视频播放地址
     */
    private String videoUrl;
    
    /**
     * AI自动生成的视频内容摘要
     */
    private String aiDescription;
    
    /**
     * 检测到的关键词列表 (JSON)
     * 格式: [{word: "北大", isUniversityRelated: true}, ...]
     */
    private String detectedKeywords;
    
    /**
     * 视频主要人物特征 (JSON)
     * 格式: {gender: "男性", ageRange: "20-24岁", voiceProfile: "年轻男性", clothing: "休闲装"}
     */
    private String mainCharacter;
    
    // ========== 核心分析维度 ==========
    
    // 1. 身份判定
    private String identityLabel;
    private String identityEvidences;  // JSON
    private String identityFusion;     // JSON
    
    // 2. 高校关联
    private String universityName;
    private String universityEvidences;  // JSON
    private String universityFusion;     // JSON
    
    // 3. 内容主题
    private String topicCategory;
    private String topicSubCategory;
    private String topicEvidences;  // JSON
    private String topicFusion;     // JSON
    
    // 4. 对学校态度（统计分类）
    private String attitudeEvidences;  // JSON
    
    // 5. 潜在舆论风险
    private String opinionRiskReason;
    private String opinionRiskEvidences;  // JSON
    private String opinionRiskFusion;     // JSON
    
    // 6. 处置建议
    private String actionSuggestion;
    private String actionDetail;
    private String actionEvidences;  // JSON
    private String actionFusion;     // JSON
    
    // ========== 时间轴数据 ==========
    
    /**
     * 时间粒度（秒）
     */
    private Integer timeGranularity;
    
    /**
     * 视频风险点时间序列 (JSON)
     * 格式: [{reason: "...", intensity: 0.8}, ...]
     */
    private String videoRisks;
    
    /**
     * 音频情绪时间序列 (JSON)
     * 格式: [{intensity: 0.9, reason: "..."}, ...]
     */
    private String audioEmotions;
    
    /**
     * 文本风险点时间序列 (JSON)
     * 格式: [{reason: "...", intensity: 0.7}, ...]
     */
    private String textRisks;
    
    /**
     * 综合风险点时间序列 (JSON)
     * 格式: [{intensity: 0.95}, ...]
     */
    private String comprehensiveRisks;
    
    /**
     * 雷达图时间段数据 (JSON)
     * 格式: [{data: [82, 88, 65, ...]}, ...]
     */
    private String radarByTime;
    
    /**
     * 全片平均雷达数据 (JSON)
     * 格式: [86, 82, 48, 44, 54, 41]
     */
    private String averageRadarData;
    
    // ========== 全模态智能事件流 ==========
    
    /**
     * 全模态智能事件流（唯一证据数据库）(JSON)
     * 格式: [{id, modality, startTime, endTime, riskScore, ...}, ...]
     */
    private String timelineEvents;
    
    // ========== 场景识别 ==========
    
    /**
     * 场景识别结果 (JSON)
     * 格式: [{id, name, icon, confidence, timeStart, timeEnd}, ...]
     */
    private String sceneRecognition;
    
    // ========== 元数据 ==========
    
    private LocalDateTime gmtCreated;
    
    private LocalDateTime gmtModified;
}

