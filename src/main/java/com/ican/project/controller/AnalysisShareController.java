package com.ican.project.controller;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.AnalysisShareCreateDTO;
import com.ican.project.model.vo.AnalysisResultVO;
import com.ican.project.model.vo.AnalysisShareVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.AnalysisShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "分析结果分享", description = "创建分析结果分享链接与公开读取分享结果")
public class AnalysisShareController {

    private final AnalysisShareService analysisShareService;

    public AnalysisShareController(AnalysisShareService analysisShareService) {
        this.analysisShareService = analysisShareService;
    }

    @PostMapping("/api/analysis/share")
    @Operation(summary = "创建分析结果分享链接")
    public Result<AnalysisShareVO> createShare(
            @RequestBody AnalysisShareCreateDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails
    ) {
        return Result.success(analysisShareService.createShare(dto.getResultId(), userDetails.getUserId()));
    }

    @GetMapping("/public/analysis/share/{token}")
    @Operation(summary = "根据分享token获取分析结果")
    public Result<AnalysisResultVO> getSharedResult(@PathVariable String token) {
        return Result.success(analysisShareService.getSharedResult(token));
    }
}