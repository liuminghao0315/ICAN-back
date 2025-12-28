package com.ican.project.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分析结果实体类
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
    
    /**
     * 综合风险评分(0-1)
     */
    private BigDecimal riskScore;
    
    /**
     * 风险等级: LOW/MEDIUM/HIGH
     */
    private String riskLevel;
    
    /**
     * 是否高校相关
     */
    private Boolean isUniversityRelated;
    
    /**
     * 识别到的高校名称
     */
    private String universityName;
    
    /**
     * 高校识别置信度
     */
    private BigDecimal universityConfidence;
    
    /**
     * 主题分类
     */
    private String topicCategory;
    
    /**
     * 主题关键词列表 (JSON)
     */
    private String topicKeywords;
    
    /**
     * 情感评分(-1到1)
     */
    private BigDecimal sentimentScore;
    
    /**
     * 情感标签
     */
    private String sentimentLabel;
    
    /**
     * 视频特征 (JSON)
     */
    private String videoFeatures;
    
    /**
     * 音频特征 (JSON)
     */
    private String audioFeatures;
    
    /**
     * 语音转文字结果
     */
    private String transcription;
    
    /**
     * 文本特征 (JSON)
     */
    private String textFeatures;
    
    /**
     * 受众分析结果 (JSON)
     */
    private String audienceAnalysis;
    
    /**
     * 传播潜力评分(0-1)
     */
    private BigDecimal spreadPotential;
    
    private LocalDateTime gmtCreated;
    
    private LocalDateTime gmtModified;
    
    /**
     * 风险等级枚举
     */
    public enum RiskLevel {
        LOW,     // 低风险 (0-0.3)
        MEDIUM,  // 中风险 (0.3-0.7)
        HIGH     // 高风险 (0.7-1.0)
    }
    
    /**
     * 情感标签枚举
     */
    public enum SentimentLabel {
        POSITIVE,  // 正面
        NEUTRAL,   // 中性
        NEGATIVE   // 负面
    }
}

