package com.ican.project.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
@Mapper
public interface FeedbackAdminUnreadMapper {

    void incrementUnread(@Param("feedbackId") String feedbackId, @Param("adminId") String adminId);

    void clearUnread(@Param("feedbackId") String feedbackId, @Param("adminId") String adminId);

    void clearAllUnread(@Param("feedbackId") String feedbackId);

    Integer getUnread(@Param("feedbackId") String feedbackId, @Param("adminId") String adminId);
}
