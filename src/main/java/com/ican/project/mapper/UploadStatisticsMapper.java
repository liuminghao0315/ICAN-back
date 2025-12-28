package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.project.model.entity.UploadStatistics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 上传统计Mapper
 */
@Mapper
public interface UploadStatisticsMapper extends BaseMapper<UploadStatistics> {
    
    /**
     * 获取用户某段时间的上传统计
     */
    @Select("SELECT * FROM upload_statistics WHERE user_id = #{userId} AND stat_date BETWEEN #{startDate} AND #{endDate} ORDER BY stat_date ASC")
    List<UploadStatistics> findByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
    
    /**
     * 获取用户某天的统计
     */
    @Select("SELECT * FROM upload_statistics WHERE user_id = #{userId} AND stat_date = #{statDate}")
    UploadStatistics findByUserIdAndDate(
            @Param("userId") String userId,
            @Param("statDate") LocalDate statDate
    );
}

