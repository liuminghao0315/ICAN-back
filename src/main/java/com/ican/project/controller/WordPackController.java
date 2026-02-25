package com.ican.project.controller;

import com.ican.project.exception.BusinessException;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.WordPackDTO;
import com.ican.project.model.vo.WordPackVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.WordPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 风险词库包控制器
 */
@RestController
@RequestMapping("/api/word-pack")
@Tag(name = "风险词库包", description = "词库包CRUD、词汇管理")
public class WordPackController {

    @Autowired
    private WordPackService wordPackService;

    /** 获取词库包列表（含词汇详情，用于词库管理页面） */
    @GetMapping("/list")
    @Operation(summary = "获取词库包列表（含词汇）")
    public Result<List<WordPackVO>> list(@AuthenticationPrincipal MyUserDetails userDetails) {
        return Result.success(wordPackService.listByUser(userDetails.getUserId()));
    }

    /** 获取词库包列表（简要，用于新建任务时选择） */
    @GetMapping("/brief")
    @Operation(summary = "获取词库包简要列表")
    public Result<List<WordPackVO>> brief(@AuthenticationPrincipal MyUserDetails userDetails) {
        return Result.success(wordPackService.listBriefByUser(userDetails.getUserId()));
    }

    /** 创建词库包 */
    @PostMapping
    @Operation(summary = "创建词库包")
    public Result<WordPackVO> create(
            @RequestBody WordPackDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            return Result.success(wordPackService.create(userDetails.getUserId(), dto));
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /** 更新词库包 */
    @PutMapping("/{packId}")
    @Operation(summary = "更新词库包")
    public Result<WordPackVO> update(
            @PathVariable String packId,
            @RequestBody WordPackDTO dto,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            return Result.success(wordPackService.update(packId, userDetails.getUserId(), dto));
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /** 删除词库包 */
    @DeleteMapping("/{packId}")
    @Operation(summary = "删除词库包")
    public Result<Void> delete(
            @PathVariable String packId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            wordPackService.delete(packId, userDetails.getUserId());
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /** 向词库包添加词汇 */
    @PostMapping("/{packId}/words")
    @Operation(summary = "添加词汇到词库包")
    public Result<Void> addWords(
            @PathVariable String packId,
            @RequestBody List<WordPackDTO.WordItem> words,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            wordPackService.addWords(packId, userDetails.getUserId(), words);
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }

    /** 删除词汇 */
    @DeleteMapping("/word/{wordId}")
    @Operation(summary = "删除词汇")
    public Result<Void> deleteWord(
            @PathVariable String wordId,
            @AuthenticationPrincipal MyUserDetails userDetails) {
        try {
            wordPackService.deleteWord(wordId, userDetails.getUserId());
            return Result.success(null);
        } catch (BusinessException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }
}
