package com.ican.project.service;

import com.ican.project.model.dto.WordPackDTO;
import com.ican.project.model.vo.WordPackVO;

import java.util.List;

/**
 * 风险词库包服务接口
 */
public interface WordPackService {

    /** 获取当前用户的所有词库包（含词汇列表） */
    List<WordPackVO> listByUser(String userId);

    /** 获取词库包列表（简要，不含词汇详情，用于新建任务时选择） */
    List<WordPackVO> listBriefByUser(String userId);

    /** 创建词库包 */
    WordPackVO create(String userId, WordPackDTO dto);

    /** 更新词库包 */
    WordPackVO update(String packId, String userId, WordPackDTO dto);

    /** 删除词库包 */
    void delete(String packId, String userId);

    /** 向词库包添加词汇（支持批量） */
    void addWords(String packId, String userId, List<WordPackDTO.WordItem> words);

    /** 删除词汇 */
    void deleteWord(String wordId, String userId);

    /** 根据词库包ID列表获取所有词汇（用于传递给Python端） */
    List<WordPackVO> getPacksWithWords(List<String> packIds);
}
