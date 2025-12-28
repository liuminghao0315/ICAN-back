package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.project.model.entity.UploadChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分片上传 Mapper
 */
@Mapper
public interface UploadChunkMapper extends BaseMapper<UploadChunk> {
    
    /**
     * 获取已上传的分片序号列表
     */
    @Select("SELECT chunk_number FROM upload_chunk WHERE file_identifier = #{fileIdentifier} AND is_uploaded = 1 ORDER BY chunk_number")
    List<Integer> getUploadedChunkNumbers(@Param("fileIdentifier") String fileIdentifier);
    
    /**
     * 统计已上传分片数量
     */
    @Select("SELECT COUNT(*) FROM upload_chunk WHERE file_identifier = #{fileIdentifier} AND is_uploaded = 1")
    int countUploadedChunks(@Param("fileIdentifier") String fileIdentifier);
}

