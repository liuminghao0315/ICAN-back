package com.ican.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分析结果分享信息")
public class AnalysisShareVO {

    @Schema(description = "分享token")
    private String token;

    @Schema(description = "分析结果ID")
    private String resultId;

    @Schema(description = "分享路径")
    private String sharePath;

    @Schema(description = "失效时间")
    private LocalDateTime expireAt;
}