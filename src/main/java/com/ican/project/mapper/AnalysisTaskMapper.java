package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.entity.AnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 分析任务 Mapper
 */
@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {
    
    /**
     * 分页查询任务列表（简化版，不再JOIN分析结果的摘要字段）
     * @param page 分页对象
     * @param userId 用户ID
     * @param status 状态筛选（可选）
     * @param sortBy 排序字段
     * @param sortOrder 排序方向 (ASC/DESC)
     * @return 任务列表（包含关联字段）
     */
    @Select("<script>" +
            "SELECT t.*, " +
            "       v.title AS video_title, v.duration AS video_duration, v.file_path AS video_file_path, " +
            "       r.id AS result_id " +
            "FROM analysis_task t " +
            "LEFT JOIN video v ON t.video_id = v.id " +
            "LEFT JOIN analysis_result r ON t.id = r.task_id " +
            "WHERE t.user_id = #{userId} " +
            "<if test='status != null and status != \"\"'>" +
            "  AND t.status = #{status} " +
            "</if>" +
            "ORDER BY " +
            "<choose>" +
            "  <when test='sortBy == \"videoDuration\"'>" +
            "    <if test='sortOrder == \"asc\"'>COALESCE(v.duration, 999999) ASC</if>" +
            "    <if test='sortOrder == \"desc\"'>COALESCE(v.duration, -1) DESC</if>" +
            "  </when>" +
            "  <otherwise>" +
            "    t.gmt_created <if test='sortOrder == \"asc\"'>ASC</if><if test='sortOrder == \"desc\"'>DESC</if>" +
            "  </otherwise>" +
            "</choose>" +
            "</script>")
    Page<Map<String, Object>> selectTasksWithJoin(
            Page<?> page,
            @Param("userId") String userId,
            @Param("status") String status,
            @Param("riskLevel") String riskLevel,
            @Param("sortBy") String sortBy,
            @Param("sortOrder") String sortOrder
    );
}

