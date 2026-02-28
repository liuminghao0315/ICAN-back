package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.project.model.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /** 将指定用户的所有未读通知标记为已读 */
    int markAllAsRead(@Param("userId") String userId);

    /** 按上下文标记已读 */
    int markByContextAsRead(@Param("userId") String userId,
                            @Param("relatedType") String relatedType,
                            @Param("feedbackId") String feedbackId,
                            @Param("videoId") String videoId,
                            @Param("targetPath") String targetPath);
}
