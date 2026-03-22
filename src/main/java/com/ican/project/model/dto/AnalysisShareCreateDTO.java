package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建分析结果分享链接请求")
public class AnalysisShareCreateDTO {

    @Schema(description = "分析结果ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String resultId;
}