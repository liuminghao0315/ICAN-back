package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.project.model.entity.AnalysisFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
@Mapper
public interface AnalysisFeedbackMapper extends BaseMapper<AnalysisFeedback> {

    /**
     * CAS 锁定反馈：仅当 status=PENDING 且 handler_id IS NULL 时才更新
     * @return 更新行数（0 表示已被其他管理员锁定）
     */
    int lockFeedback(@Param("id") String id, @Param("handlerId") String handlerId);
}
