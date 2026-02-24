package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.project.model.entity.Folder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 文件夹 Mapper
 */
@Mapper
public interface FolderMapper extends BaseMapper<Folder> {

    /**
     * 统计用户每个文件夹下的视频数量
     */
    @Select("SELECT v.folder_id AS folderId, COUNT(*) AS cnt " +
            "FROM video v WHERE v.user_id = #{userId} AND v.folder_id IS NOT NULL " +
            "GROUP BY v.folder_id")
    List<Map<String, Object>> countVideosByFolder(@Param("userId") String userId);

    /**
     * 统计用户未分类视频数量（folder_id IS NULL）
     */
    @Select("SELECT COUNT(*) FROM video WHERE user_id = #{userId} AND folder_id IS NULL")
    int countUncategorizedVideos(@Param("userId") String userId);

    /**
     * 统计用户全部视频数量
     */
    @Select("SELECT COUNT(*) FROM video WHERE user_id = #{userId}")
    int countAllVideos(@Param("userId") String userId);
}
