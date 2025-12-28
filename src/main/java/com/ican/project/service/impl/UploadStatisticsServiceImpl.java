package com.ican.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.project.mapper.UploadStatisticsMapper;
import com.ican.project.model.entity.UploadStatistics;
import com.ican.project.service.UploadStatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 上传统计服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadStatisticsServiceImpl implements UploadStatisticsService {
    
    private final UploadStatisticsMapper uploadStatisticsMapper;
    
    @Override
    @Transactional
    public void recordUpload(String userId, Long fileSize) {
        LocalDate today = LocalDate.now();
        
        // 查找今天是否已有记录
        UploadStatistics existing = uploadStatisticsMapper.findByUserIdAndDate(userId, today);
        
        if (existing != null) {
            // 更新已有记录
            existing.setUploadCount(existing.getUploadCount() + 1);
            existing.setTotalSize(existing.getTotalSize() + (fileSize != null ? fileSize : 0));
            existing.setGmtModified(LocalDateTime.now());
            uploadStatisticsMapper.updateById(existing);
            log.info("更新上传统计: userId={}, date={}, count={}", userId, today, existing.getUploadCount());
        } else {
            // 创建新记录
            UploadStatistics stat = UploadStatistics.builder()
                    .userId(userId)
                    .statDate(today)
                    .uploadCount(1)
                    .totalSize(fileSize != null ? fileSize : 0)
                    .gmtCreated(LocalDateTime.now())
                    .gmtModified(LocalDateTime.now())
                    .build();
            uploadStatisticsMapper.insert(stat);
            log.info("创建上传统计: userId={}, date={}", userId, today);
        }
    }
    
    @Override
    public List<Map<String, Object>> getUploadTrend(String userId, int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);
        
        // 获取数据库中的统计记录
        List<UploadStatistics> stats = uploadStatisticsMapper.findByUserIdAndDateRange(userId, startDate, endDate);
        
        // 转换为Map方便查询
        Map<LocalDate, UploadStatistics> statsMap = new HashMap<>();
        for (UploadStatistics stat : stats) {
            statsMap.put(stat.getStatDate(), stat);
        }
        
        // 生成完整的日期列表（包括没有数据的日期）
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = endDate.minusDays(i);
            Map<String, Object> item = new HashMap<>();
            item.put("date", date.toString());
            item.put("displayDate", (date.getMonthValue()) + "/" + date.getDayOfMonth());
            
            UploadStatistics stat = statsMap.get(date);
            if (stat != null) {
                item.put("count", stat.getUploadCount());
                item.put("size", stat.getTotalSize());
            } else {
                item.put("count", 0);
                item.put("size", 0);
            }
            
            result.add(item);
        }
        
        return result;
    }
    
    @Override
    public Map<String, Object> getTotalStats(String userId) {
        LambdaQueryWrapper<UploadStatistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UploadStatistics::getUserId, userId);
        List<UploadStatistics> allStats = uploadStatisticsMapper.selectList(wrapper);
        
        int totalCount = 0;
        long totalSize = 0;
        
        for (UploadStatistics stat : allStats) {
            totalCount += stat.getUploadCount();
            totalSize += stat.getTotalSize();
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("totalUploadCount", totalCount);
        result.put("totalUploadSize", totalSize);
        
        return result;
    }
}

