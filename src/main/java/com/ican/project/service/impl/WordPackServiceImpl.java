package com.ican.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.WordPackMapper;
import com.ican.project.mapper.WordPackWordMapper;
import com.ican.project.model.dto.WordPackDTO;
import com.ican.project.model.entity.WordPack;
import com.ican.project.model.entity.WordPackWord;
import com.ican.project.model.vo.WordPackVO;
import com.ican.project.service.WordPackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WordPackServiceImpl implements WordPackService {

    @Autowired
    private WordPackMapper wordPackMapper;

    @Autowired
    private WordPackWordMapper wordPackWordMapper;

    @Override
    public List<WordPackVO> listByUser(String userId) {
        LambdaQueryWrapper<WordPack> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WordPack::getUserId, userId).orderByDesc(WordPack::getGmtCreated);
        List<WordPack> packs = wordPackMapper.selectList(wrapper);

        return packs.stream().map(pack -> {
            List<WordPackWord> words = wordPackWordMapper.selectList(
                    new LambdaQueryWrapper<WordPackWord>().eq(WordPackWord::getPackId, pack.getId())
            );
            return toVO(pack, words);
        }).collect(Collectors.toList());
    }

    @Override
    public List<WordPackVO> listBriefByUser(String userId) {
        LambdaQueryWrapper<WordPack> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WordPack::getUserId, userId).orderByDesc(WordPack::getGmtCreated);
        List<WordPack> packs = wordPackMapper.selectList(wrapper);

        return packs.stream().map(pack -> {
            Long count = wordPackWordMapper.selectCount(
                    new LambdaQueryWrapper<WordPackWord>().eq(WordPackWord::getPackId, pack.getId())
            );
            return WordPackVO.builder()
                    .id(pack.getId())
                    .name(pack.getName())
                    .description(pack.getDescription())
                    .wordCount(count.intValue())
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public WordPackVO create(String userId, WordPackDTO dto) {
        // 名称去重（trim后比较）
        String trimmedName = dto.getName().trim();
        long nameCount = wordPackMapper.selectCount(
                new LambdaQueryWrapper<WordPack>()
                        .eq(WordPack::getUserId, userId)
                        .apply("TRIM(name) = {0}", trimmedName)
        );
        if (nameCount > 0) {
            throw new BusinessException("词库包「" + trimmedName + "」已存在，请换一个名称");
        }

        WordPack pack = WordPack.builder()
                .userId(userId)
                .name(trimmedName)
                .description(dto.getDescription() != null ? dto.getDescription().trim() : "")
                .build();
        wordPackMapper.insert(pack);

        List<WordPackWord> words = new ArrayList<>();
        if (dto.getWords() != null) {
            Set<String> addedTexts = new HashSet<>();
            for (WordPackDTO.WordItem item : dto.getWords()) {
                if (item.getText() == null || item.getText().trim().isEmpty()) continue;
                String text = item.getText().trim();
                if (addedTexts.contains(text)) continue;
                addedTexts.add(text);
                WordPackWord word = WordPackWord.builder()
                        .packId(pack.getId())
                        .text(text)
                        .risk(item.getRisk() != null ? item.getRisk() : "medium")
                        .build();
                wordPackWordMapper.insert(word);
                words.add(word);
            }
        }
        return toVO(pack, words);
    }

    @Override
    @Transactional
    public WordPackVO update(String packId, String userId, WordPackDTO dto) {
        WordPack pack = wordPackMapper.selectById(packId);
        if (pack == null || !pack.getUserId().equals(userId)) {
            throw new BusinessException("词库包不存在或无权限");
        }
        if (dto.getName() != null) {
            String trimmedName = dto.getName().trim();
            // 名称去重（排除自身）
            long nameCount = wordPackMapper.selectCount(
                    new LambdaQueryWrapper<WordPack>()
                            .eq(WordPack::getUserId, userId)
                            .ne(WordPack::getId, packId)
                            .apply("TRIM(name) = {0}", trimmedName)
            );
            if (nameCount > 0) {
                throw new BusinessException("词库包「" + trimmedName + "」已存在，请换一个名称");
            }
            pack.setName(trimmedName);
        }
        if (dto.getDescription() != null) pack.setDescription(dto.getDescription().trim());
        wordPackMapper.updateById(pack);

        List<WordPackWord> words = wordPackWordMapper.selectList(
                new LambdaQueryWrapper<WordPackWord>().eq(WordPackWord::getPackId, packId)
        );
        return toVO(pack, words);
    }

    @Override
    @Transactional
    public void delete(String packId, String userId) {
        WordPack pack = wordPackMapper.selectById(packId);
        if (pack == null || !pack.getUserId().equals(userId)) {
            throw new BusinessException("词库包不存在或无权限");
        }
        // 级联删除词汇（数据库外键已设置CASCADE，但显式删除更安全）
        wordPackWordMapper.delete(
                new LambdaQueryWrapper<WordPackWord>().eq(WordPackWord::getPackId, packId)
        );
        wordPackMapper.deleteById(packId);
    }

    @Override
    @Transactional
    public void addWords(String packId, String userId, List<WordPackDTO.WordItem> words) {
        WordPack pack = wordPackMapper.selectById(packId);
        if (pack == null || !pack.getUserId().equals(userId)) {
            throw new BusinessException("词库包不存在或无权限");
        }
        if (words == null) return;
        // 获取已有词汇集合，用于去重
        List<WordPackWord> existing = wordPackWordMapper.selectList(
                new LambdaQueryWrapper<WordPackWord>().eq(WordPackWord::getPackId, packId)
        );
        Set<String> existingTexts = existing.stream()
                .map(w -> w.getText().trim())
                .collect(Collectors.toSet());

        for (WordPackDTO.WordItem item : words) {
            if (item.getText() == null || item.getText().trim().isEmpty()) continue;
            String text = item.getText().trim();
            if (existingTexts.contains(text)) continue; // 跳过重复词汇
            existingTexts.add(text); // 防止批量添加时自身重复
            WordPackWord word = WordPackWord.builder()
                    .packId(packId)
                    .text(text)
                    .risk(item.getRisk() != null ? item.getRisk() : "medium")
                    .build();
            wordPackWordMapper.insert(word);
        }
    }

    @Override
    public void deleteWord(String wordId, String userId) {
        WordPackWord word = wordPackWordMapper.selectById(wordId);
        if (word == null) return;
        WordPack pack = wordPackMapper.selectById(word.getPackId());
        if (pack == null || !pack.getUserId().equals(userId)) {
            throw new BusinessException("无权限删除该词汇");
        }
        wordPackWordMapper.deleteById(wordId);
    }

    @Override
    public List<WordPackVO> getPacksWithWords(List<String> packIds) {
        if (packIds == null || packIds.isEmpty()) return Collections.emptyList();
        List<WordPack> packs = wordPackMapper.selectBatchIds(packIds);
        return packs.stream().map(pack -> {
            List<WordPackWord> words = wordPackWordMapper.selectList(
                    new LambdaQueryWrapper<WordPackWord>().eq(WordPackWord::getPackId, pack.getId())
            );
            return toVO(pack, words);
        }).collect(Collectors.toList());
    }

    private WordPackVO toVO(WordPack pack, List<WordPackWord> words) {
        List<WordPackVO.WordItem> wordItems = words != null
                ? words.stream().map(w -> WordPackVO.WordItem.builder()
                        .id(w.getId()).text(w.getText()).risk(w.getRisk()).build())
                        .collect(Collectors.toList())
                : Collections.emptyList();

        return WordPackVO.builder()
                .id(pack.getId())
                .name(pack.getName())
                .description(pack.getDescription())
                .wordCount(wordItems.size())
                .words(wordItems)
                .build();
    }
}
