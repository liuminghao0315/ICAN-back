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
     * 分页查询任务列表（JOIN分析结果表，支持风险等级筛选和风险分数排序）
     * @param page 分页对象
     * @param userId 用户ID
     * @param status 状态筛选（可选）
     * @param riskLevel 风险等级筛选 HIGH/MEDIUM/LOW（可选）
     * @param sortBy 排序字段：gmtCreated / riskScore / videoDuration
     * @param sortOrder 排序方向 asc/desc
     * @param folderIds 文件夹ID列表（可选，用于按文件夹过滤）
     * @param uncategorizedOnly 是否仅查未分类视频
     * @return 任务列表（包含关联字段）
     */
    @Select("<script>" +
            "SELECT t.*, " +
            "       v.title AS video_title, v.duration AS video_duration, v.file_path AS video_file_path, " +
            "       v.thumbnail_path AS video_thumbnail_path, " +
            "       v.source_type AS video_source_type, v.source_url AS video_source_url, " +
            "       v.folder_id AS video_folder_id, " +
            "       f.name AS video_folder_name, " +
            "       r.id AS result_id, " +
            "       CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)) AS risk_score_val, " +
            "       CASE WHEN uf.id IS NOT NULL THEN 1 ELSE 0 END AS is_favorited " +
            "FROM analysis_task t " +
            "LEFT JOIN video v ON t.video_id = v.id " +
            "LEFT JOIN folder f ON v.folder_id = f.id " +
            "LEFT JOIN analysis_result r ON t.id = r.task_id " +
            "LEFT JOIN user_favorite uf ON t.id = uf.task_id AND uf.user_id = #{userId} " +
            "WHERE t.user_id = #{userId} " +
            "<if test='status != null and status != \"\"'>" +
            "  AND t.status = #{status} " +
            "</if>" +
            "<if test='folderIds != null and folderIds.size() > 0'>" +
            "  AND v.folder_id IN <foreach item='fid' collection='folderIds' open='(' separator=',' close=')'>#{fid}</foreach> " +
            "</if>" +
            "<if test='uncategorizedOnly == true'>" +
            "  AND (v.folder_id IS NULL) " +
            "</if>" +
            "<if test='riskLevel != null and riskLevel != \"\"'>" +
            "  AND t.status = 'COMPLETED' " +
            "  AND r.id IS NOT NULL " +
            "  <choose>" +
            "    <when test='riskLevel == \"HIGH\"'>" +
            "      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)) >= 70 " +
            "    </when>" +
            "    <when test='riskLevel == \"MEDIUM\"'>" +
            "      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)) >= 40 " +
            "      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)) &lt; 70 " +
            "    </when>" +
            "    <when test='riskLevel == \"LOW\"'>" +
            "      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)) &lt; 40 " +
            "    </when>" +
            "  </choose>" +
            "</if>" +
            "ORDER BY " +
            "<choose>" +
            "  <when test='sortBy == \"riskScore\"'>" +
            "    <if test='sortOrder == \"asc\"'>COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)), -1) ASC</if>" +
            "    <if test='sortOrder == \"desc\"'>COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)), -1) DESC</if>" +
            "  </when>" +
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
            @Param("sortOrder") String sortOrder,
            @Param("folderIds") java.util.List<String> folderIds,
            @Param("uncategorizedOnly") boolean uncategorizedOnly
    );
}

