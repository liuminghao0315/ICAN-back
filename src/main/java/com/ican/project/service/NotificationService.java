package com.ican.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ican.project.model.entity.Notification;
import com.ican.project.model.vo.NotificationVO;

import java.util.List;

public interface NotificationService extends IService<Notification> {

    /** 发送通知给指定用户 */
    void sendNotification(String userId, String type, String title, String content, String relatedId);

    /** 批量发送通知给多个用户 */
    void sendNotificationToUsers(List<String> userIds, String type, String title, String content, String relatedId);

    /** 获取当前用户的通知列表 */
    Page<NotificationVO> getNotificationList(String userId, int page, int size);

    /** 获取未读通知数量 */
    int getUnreadCount(String userId);

    /** 标记单条已读 */
    void markAsRead(String notificationId, String userId);

    /** 全部标记已读 */
    void markAllAsRead(String userId);
}
