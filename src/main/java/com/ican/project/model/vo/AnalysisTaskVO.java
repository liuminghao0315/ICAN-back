package com.ican.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分析任务VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分析任务信息")
public class AnalysisTaskVO {
    
    @Schema(description = "任务ID")
    private String id;
    
    @Schema(description = "视频ID")
    private String videoId;
    
    @Schema(description = "视频标题")
    private String videoTitle;
    
    @Schema(description = "视频URL")
    private String videoUrl;
    
    @Schema(description = "任务类型: FULL_ANALYSIS/VIDEO_ONLY/AUDIO_ONLY/TEXT_ONLY")
    private String taskType;
    
    @Schema(description = "任务状态: DOWNLOADING/PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED")
    private String status;
    
    @Schema(description = "处理进度(0-100)")
    private Integer progress;
    
    @Schema(description = "错误信息")
    private String errorMessage;
    
    @Schema(description = "开始处理时间")
    private LocalDateTime startedAt;
    
    @Schema(description = "完成时间")
    private LocalDateTime completedAt;
    
    @Schema(description = "创建时间")
    private LocalDateTime gmtCreated;
    
    @Schema(description = "是否有分析结果")
    private Boolean hasResult;
    
    @Schema(description = "分析结果ID（如果已完成）")
    private String resultId;
    
    @Schema(description = "视频时长（秒）")
    private Double videoDuration;
    
    // ======== 分析结果摘要字段 ========
    @Schema(description = "风险分数（0.0-1.0）")
    private Double riskScore;
    
    @Schema(description = "风险等级: LOW/MEDIUM/HIGH")
    private String riskLevel;
    
    @Schema(description = "情感标签: POSITIVE/NEUTRAL/NEGATIVE")
    private String sentimentLabel;
    
    @Schema(description = "来源类型: LOCAL_UPLOAD/URL_IMPORT")
    private String sourceType;
    
    @Schema(description = "来源URL")
    private String sourceUrl;
    
    @Schema(description = "视频缩略图URL")
    private String thumbnailUrl;
    
    @Schema(description = "AI提取关键词列表")
    private java.util.List<String> keywords;
    
    @Schema(description = "涉及高校名称")
    private String universityName;
    
    @Schema(description = "内容主题分类")
    private String topicCategory;

    @Schema(description = "失败类型（仅 status=FAILED 时有值）: DOWNLOAD_FAILED / ANALYSIS_FAILED")
    private String failureType;

    @Schema(description = "视频所在文件夹ID（直接归属，非递归）")
    private String folderId;

    @Schema(description = "视频所在文件夹名称（直接归属，非递归）")
    private String folderName;

    @Schema(description = "是否已被当前用户收藏")
    private Boolean isFavorited;

    @Schema(description = "挂载的风险词库包列表")
    private java.util.List<WordPackVO> wordPacks;
}

