package com.ican.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ican.project.mapper.NotificationMapper;
import com.ican.project.model.entity.Notification;
import com.ican.project.model.vo.NotificationVO;
import com.ican.project.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification>
        implements NotificationService {

    @Autowired
    private NotificationMapper notificationMapper;

    @Override
    public void sendNotification(String userId, String type, String title, String content, String relatedId) {
        Notification n = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .content(content)
                .relatedId(relatedId)
                .isRead(false)
                .gmtCreated(LocalDateTime.now())
                .build();
        save(n);
    }

    @Override
    public void sendNotificationToUsers(List<String> userIds, String type, String title, String content, String relatedId) {
        if (userIds == null || userIds.isEmpty()) return;
        for (String uid : userIds) {
            sendNotification(uid, type, title, content, relatedId);
        }
    }

    @Override
    public Page<NotificationVO> getNotificationList(String userId, int page, int size) {
        Page<Notification> entityPage = new Page<>(page, size);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getGmtCreated);
        notificationMapper.selectPage(entityPage, wrapper);

        Page<NotificationVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public int getUnreadCount(String userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false);
        return notificationMapper.selectCount(wrapper).intValue();
    }

    @Override
    public void markAsRead(String notificationId, String userId) {
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getId, notificationId)
                .eq(Notification::getUserId, userId)
                .set(Notification::getIsRead, true);
        update(wrapper);
    }

    @Override
    public void markAllAsRead(String userId) {
        notificationMapper.markAllAsRead(userId);
    }

    private NotificationVO toVO(Notification n) {
        return NotificationVO.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .relatedId(n.getRelatedId())
                .isRead(n.getIsRead())
                .gmtCreated(n.getGmtCreated())
                .build();
    }
}
