package com.ican.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分析结果VO（完全适配新前端数据结构）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分析结果信息")
public class AnalysisResultVO {
    
    @Schema(description = "结果ID")
    private String id;
    
    @Schema(description = "任务ID")
    private String taskId;
    
    @Schema(description = "是否高校相关（根据detectedKeywords自动判断）")
    private Boolean isUniversityRelated;
    
    // ========== 视频基本信息 ==========
    
    @Schema(description = "视频基本信息")
    private VideoInfo videoInfo;
    
    // ========== 核心分析维度 ==========
    
    @Schema(description = "身份判定分析")
    private IdentityAnalysis identity;
    
    @Schema(description = "高校关联分析")
    private UniversityAnalysis university;
    
    @Schema(description = "内容主题分析")
    private TopicAnalysis topic;
    
    @Schema(description = "对学校态度分析")
    private AttitudeAnalysis attitude;
    
    @Schema(description = "潜在舆论风险分析")
    private OpinionRiskAnalysis opinionRisk;
    
    @Schema(description = "处置建议")
    private ActionSuggestion action;
    
    // ========== 时间轴数据 ==========
    
    @Schema(description = "时间轴数据")
    private TimelineData timelineData;
    
    // ========== 全模态智能事件流 ==========
    
    @Schema(description = "全模态智能事件流（唯一证据数据库）")
    private List<Object> timelineEvents;
    
    // ========== 场景识别 ==========
    
    @Schema(description = "场景识别结果")
    private List<Object> sceneRecognition;
    
    @Schema(description = "挂载的风险词库包列表")
    private List<WordPackVO> wordPacks;
    
    @Schema(description = "创建时间")
    private LocalDateTime gmtCreated;
    
    // ========== 内部类定义 ==========
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoInfo {
        private String videoId;
        private String videoUrl;
        private String fileName;
        private Double duration;
        private String uploadSource;
        private String description;
        private List<Object> detectedKeywords;
        private Map<String, Object> mainCharacter;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdentityAnalysis {
        private String identityLabel;
        private List<Object> evidences;
        private Map<String, Object> modalityFusion;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UniversityAnalysis {
        private String universityName;
        private List<Object> evidences;
        private Map<String, Object> modalityFusion;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopicAnalysis {
        private String topicCategory;
        private String topicSubCategory;
        private List<Object> evidences;
        private Map<String, Object> modalityFusion;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttitudeAnalysis {
        private List<Object> evidences;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpinionRiskAnalysis {
        private String riskReason;
        private List<Object> evidences;
        private Map<String, Object> modalityFusion;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionSuggestion {
        private String actionSuggestion;
        private String actionDetail;
        private List<Object> evidences;
        private Map<String, Object> modalityFusion;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineData {
        private Integer timeGranularity;
        private List<Object> videoRisks;
        private List<Object> audioEmotions;
        private List<Object> textRisks;
        private List<Object> comprehensiveRisks;
        private List<Object> radarByTime;
        private List<Integer> averageRadarData;
    }
}

