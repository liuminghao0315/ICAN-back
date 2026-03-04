package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.project.model.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 用户收藏 Mapper
 */
@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    /**
     * 分页查询用户收藏列表（JOIN 分析任务、视频、分析结果表）
     * 默认按收藏时间倒序（最新收藏在最前）
     *
     * @param page      分页对象
     * @param userId    用户ID
     * @param riskLevel 风险等级筛选（可选）
     * @param keyword   关键词搜索（可选，匹配视频标题）
     * @param sortField 排序字段：gmtCreated（收藏时间）/ riskScore
     * @param sortDir   排序方向：asc / desc
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
            "       uf.gmt_created AS favorite_time " +
            "FROM user_favorite uf " +
            "INNER JOIN analysis_task t ON uf.task_id = t.id " +
            "LEFT JOIN video v ON t.video_id = v.id " +
            "LEFT JOIN folder f ON v.folder_id = f.id " +
            "LEFT JOIN analysis_result r ON t.id = r.task_id " +
            "WHERE uf.user_id = #{userId} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "  AND v.title LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "<if test='sourceType != null and sourceType != \"\"'>" +
            "  AND v.source_type = #{sourceType} " +
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
            "  <when test='sortField == \"riskScore\"'>" +
            "    <if test='sortDir == \"asc\"'>COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)), -1) ASC</if>" +
            "    <if test='sortDir != \"asc\"'>COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.opinion_risk_fusion, '$.finalScore')) AS DECIMAL(10,2)), -1) DESC</if>" +
            "  </when>" +
            "  <otherwise>" +
            "    uf.gmt_created <if test='sortDir == \"asc\"'>ASC</if><if test='sortDir != \"asc\"'>DESC</if>" +
            "  </otherwise>" +
            "</choose>" +
            "</script>")
    Page<Map<String, Object>> selectFavoritesWithJoin(
            Page<?> page,
            @Param("userId") String userId,
            @Param("riskLevel") String riskLevel,
            @Param("keyword") String keyword,
            @Param("sourceType") String sourceType,
            @Param("sortField") String sortField,
            @Param("sortDir") String sortDir
    );
}
