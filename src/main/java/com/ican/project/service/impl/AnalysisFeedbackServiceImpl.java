package com.ican.project.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.AnalysisFeedbackMapper;
import com.ican.project.mapper.FeedbackAdminUnreadMapper;
import com.ican.project.mapper.AnalysisResultMapper;
import com.ican.project.mapper.UserMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.dto.FeedbackCreateDTO;
import com.ican.project.model.entity.AnalysisFeedback;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.User;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.FeedbackVO;
import com.ican.project.service.AnalysisFeedbackService;
import com.ican.project.service.NotificationService;
import com.ican.project.service.UserService;
import com.ican.project.websocket.TaskProgressWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisFeedbackServiceImpl extends ServiceImpl<AnalysisFeedbackMapper, AnalysisFeedback>
        implements AnalysisFeedbackService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisFeedbackServiceImpl.class);

    @Autowired
    private AnalysisFeedbackMapper feedbackMapper;

    @Autowired
    private FeedbackAdminUnreadMapper adminUnreadMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private AnalysisResultMapper analysisResultMapper;

    @Autowired
    private UserService userService;

    @Autowired
    @Lazy
    private NotificationService notificationService;

    @Override
    public FeedbackVO submitFeedback(String userId, FeedbackCreateDTO dto) {
        // 查询该用户对该视频是否已有反馈
        LambdaQueryWrapper<AnalysisFeedback> existWrapper = new LambdaQueryWrapper<>();
        existWrapper.eq(AnalysisFeedback::getUserId, userId)
                .eq(AnalysisFeedback::getVideoId, dto.getVideoId());
        AnalysisFeedback existing = feedbackMapper.selectOne(existWrapper);

        if (existing != null) {
            // 追加消息到聊天记录（纯聊天，不改类型和模块）
            JSONArray messages = parseMessages(existing.getContent());
            JSONObject newMsg = new JSONObject();
            newMsg.put("role", "user");
            newMsg.put("text", dto.getContent());
            newMsg.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            messages.add(newMsg);

            existing.setContent(messages.toJSONString());

            // 用户追加消息，给相关管理员未读+1（新表，每人独立）

            // 状态策略：只有已关闭的反馈才重新打开，进行中的不打扰
            String oldStatus = existing.getStatus();
            boolean reopened = false;
            if ("RESOLVED".equals(oldStatus) || "REJECTED".equals(oldStatus)) {
                existing.setStatus("PENDING");
                reopened = true;
            }
            // PENDING / PROCESSING 状态下用户追加消息，状态不变

            existing.setGmtModified(LocalDateTime.now());
            updateById(existing);

            // 只有重新打开时才发通知铃铛
            if (reopened) {
                // 只通知原处理人；若无处理人则通知所有管理员
                String reopenHandlerId = existing.getHandlerId();
                if (reopenHandlerId != null && !reopenHandlerId.isEmpty()) {
                    adminUnreadMapper.incrementUnread(existing.getId(), reopenHandlerId);
                    TaskProgressWebSocket.sendFeedbackNew(reopenHandlerId, existing.getId());
                } else {
                    List<String> adminIds = userService.getAdminUserIds();
                    for (String adminId : adminIds) {
                        adminUnreadMapper.incrementUnread(existing.getId(), adminId);
                    }
                    notifyAdmins(existing);
                }
                // 推送给用户自己：状态已变回 PENDING
                TaskProgressWebSocket.sendFeedbackUpdated(userId, existing.getId(), "PENDING");
            } else {
                // 已有处理人：只推给处理人；未锁定：推给所有管理员
                String handlerId = existing.getHandlerId();
                if (handlerId != null && !handlerId.isEmpty()) {
                    adminUnreadMapper.incrementUnread(existing.getId(), handlerId);
                    TaskProgressWebSocket.sendFeedbackNew(handlerId, existing.getId());
                    // 给其他管理员同步会话内容（仅刷新，不增加未读）
                    broadcastFeedbackSyncToOtherAdmins(handlerId, existing.getId());
                } else {
                    List<String> adminIds = userService.getAdminUserIds();
                    for (String adminId : adminIds) {
                        adminUnreadMapper.incrementUnread(existing.getId(), adminId);
                        TaskProgressWebSocket.sendFeedbackNew(adminId, existing.getId());
                    }
                }
            }
            return toVO(existing);
        }

        // 首次反馈：构建聊天记录格式
        JSONArray messages = new JSONArray();
        JSONObject firstMsg = new JSONObject();
        firstMsg.put("role", "user");
        firstMsg.put("text", dto.getContent());
        firstMsg.put("type", dto.getFeedbackType());
        firstMsg.put("module", dto.getModule());
        firstMsg.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        messages.add(firstMsg);

        // 获取视频标题
        Video video = videoMapper.selectById(dto.getVideoId());
        String videoTitle = video != null ? video.getTitle() : null;

        // 构建分析快照
        String snapshot = buildAnalysisSnapshot(dto.getTaskId());

        AnalysisFeedback feedback = AnalysisFeedback.builder()
                .userId(userId)
                .taskId(dto.getTaskId())
                .videoId(dto.getVideoId())
                .feedbackType(dto.getFeedbackType())
                .module(dto.getModule())
                .content(messages.toJSONString())
                .videoTitle(videoTitle)
                .analysisSnapshot(snapshot)
                .status("PENDING")
                .userUnread(0)
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        save(feedback);

        // 给所有管理员未读+1
        List<String> adminIds = userService.getAdminUserIds();
        for (String adminId : adminIds) {
            adminUnreadMapper.incrementUnread(feedback.getId(), adminId);
        }
        notifyAdmins(feedback);
        return toVO(feedback);
    }

    @Override
    public FeedbackVO getMyFeedbackByVideo(String userId, String videoId) {
        LambdaQueryWrapper<AnalysisFeedback> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisFeedback::getUserId, userId)
                .eq(AnalysisFeedback::getVideoId, videoId);
        AnalysisFeedback feedback = feedbackMapper.selectOne(wrapper);
        return feedback != null ? toVO(feedback) : null;
    }

    @Override
    public Page<FeedbackVO> getMyFeedbacks(String userId, int page, int size) {
        Page<AnalysisFeedback> entityPage = new Page<>(page, size);
        LambdaQueryWrapper<AnalysisFeedback> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisFeedback::getUserId, userId)
                .orderByDesc(AnalysisFeedback::getGmtCreated);
        feedbackMapper.selectPage(entityPage, wrapper);
        return convertPage(entityPage);
    }

    @Override
    public Page<FeedbackVO> getAdminFeedbackList(int page, int size, String status, String adminId, boolean onlyMine) {
        Page<AnalysisFeedback> entityPage = new Page<>(page, size);
        LambdaQueryWrapper<AnalysisFeedback> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(AnalysisFeedback::getStatus, status);
        }
        if (onlyMine) {
            wrapper.eq(AnalysisFeedback::getHandlerId, adminId);
        }
        wrapper.orderByDesc(AnalysisFeedback::getGmtCreated);
        feedbackMapper.selectPage(entityPage, wrapper);
        return convertPageForAdmin(entityPage, adminId);
    }

    @Override
    public void lockFeedback(String feedbackId, String adminUserId) {
        int rows = feedbackMapper.lockFeedback(feedbackId, adminUserId);
        if (rows == 0) {
            throw new BusinessException("该反馈已被其他管理员处理或状态已变更");
        }

        // 插入系统消息：告知用户反馈已被接管
        AnalysisFeedback feedback = feedbackMapper.selectById(feedbackId);
        User admin = userMapper.selectById(adminUserId);
        String adminName = admin != null ? admin.getName() : "管理员";
        if (feedback != null) {
            JSONArray messages = parseMessages(feedback.getContent());
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("text", adminName + " 已接手此反馈，正在为您处理，请耐心等待。");
            sysMsg.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            messages.add(sysMsg);
            feedback.setContent(messages.toJSONString());
            feedback.setUserUnread((feedback.getUserUnread() == null ? 0 : feedback.getUserUnread()) + 1);
            // 锁定时清零所有管理员的未读数（新表）
            adminUnreadMapper.clearAllUnread(feedbackId);
            feedback.setGmtModified(LocalDateTime.now());
            updateById(feedback);
            // 推送给用户
            TaskProgressWebSocket.sendFeedbackUpdated(feedback.getUserId(), feedbackId, feedback.getStatus());
        }

        // 广播给其他在线管理员：该反馈已被锁定（携带处理人信息）
        List<String> adminIds = userService.getAdminUserIds();
        for (String aid : adminIds) {
            if (!aid.equals(adminUserId)) {
                TaskProgressWebSocket.sendFeedbackLocked(aid, feedbackId, adminUserId, adminName);
            }
        }
    }

    @Override
    public void replyFeedback(String feedbackId, String adminUserId, String reply) {
        AnalysisFeedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        boolean isHandler = adminUserId.equals(feedback.getHandlerId());
        String st = feedback.getStatus();
        if (!isHandler || "RESOLVED".equals(st) || "REJECTED".equals(st)) {
            throw new BusinessException("操作失败，您可能不是该反馈的处理人，或反馈已关闭");
        }

        JSONArray messages = parseMessages(feedback.getContent());
        JSONObject adminMsg = new JSONObject();
        adminMsg.put("role", "admin");
        adminMsg.put("text", reply);
        adminMsg.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        messages.add(adminMsg);

        feedback.setContent(messages.toJSONString());
        feedback.setAdminReply(reply);
        feedback.setUserUnread((feedback.getUserUnread() == null ? 0 : feedback.getUserUnread()) + 1);
        feedback.setGmtModified(LocalDateTime.now());
        updateById(feedback);

        notificationService.sendNotification(
                feedback.getUserId(),
                "FEEDBACK_REPLIED",
                "《" + getVideoTitle(feedback) + "》收到管理员回复",
                quoteAndTruncate(reply),
                feedbackId,
                "FEEDBACK",
                feedbackId,
                feedback.getVideoId(),
                "/analysis"
        );
        // WebSocket 实时推送给用户
        TaskProgressWebSocket.sendFeedbackUpdated(feedback.getUserId(), feedbackId, feedback.getStatus());
        // 实时同步给其他管理员（仅刷新，不增加未读）
        broadcastFeedbackSyncToOtherAdmins(adminUserId, feedbackId);
    }

    @Override
    public void closeFeedback(String feedbackId, String adminUserId, String status) {
        if (!"RESOLVED".equals(status) && !"REJECTED".equals(status)) {
            throw new BusinessException("无效的处理状态");
        }
        AnalysisFeedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        if (!adminUserId.equals(feedback.getHandlerId())) {
            throw new BusinessException("操作失败，您不是该反馈的处理人");
        }
        if ("RESOLVED".equals(feedback.getStatus()) || "REJECTED".equals(feedback.getStatus())) {
            throw new BusinessException("该反馈已关闭");
        }

        String statusText = "RESOLVED".equals(status) ? "已解决" : "已驳回";

        // 插入系统消息
        JSONArray messages = parseMessages(feedback.getContent());
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("text", "此反馈已被管理员标记为「" + statusText + "」，感谢您的反馈与配合。");
        sysMsg.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        messages.add(sysMsg);

        feedback.setContent(messages.toJSONString());
        feedback.setStatus(status);
        feedback.setUserUnread((feedback.getUserUnread() == null ? 0 : feedback.getUserUnread()) + 1);
        feedback.setGmtModified(LocalDateTime.now());
        updateById(feedback);
        notificationService.sendNotification(
                feedback.getUserId(),
                "FEEDBACK_REPLIED",
                "《" + getVideoTitle(feedback) + "》反馈处理结果：" + statusText,
                quoteAndTruncate("此反馈已被管理员标记为「" + statusText + "」"),
                feedbackId,
                "FEEDBACK",
                feedbackId,
                feedback.getVideoId(),
                "/analysis"
        );
        // WebSocket 实时推送给用户
        TaskProgressWebSocket.sendFeedbackUpdated(feedback.getUserId(), feedbackId, status);
        // 实时同步给其他管理员（仅刷新，不增加未读）
        broadcastFeedbackSyncToOtherAdmins(adminUserId, feedbackId);
    }

    @Override
    public FeedbackVO getFeedbackById(String feedbackId) {
        AnalysisFeedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new BusinessException("反馈不存在");
        }
        return toVO(feedback);
    }

    @Override
    public void clearUnread(String feedbackId, boolean isAdmin, String userId) {
        if (isAdmin) {
            adminUnreadMapper.clearUnread(feedbackId, userId);
        } else {
            AnalysisFeedback feedback = feedbackMapper.selectById(feedbackId);
            if (feedback == null) return;
            feedback.setUserUnread(0);
            feedback.setGmtModified(LocalDateTime.now());
            updateById(feedback);
        }
    }

    // 广播会话变更给除操作者外的所有管理员（仅同步，不改未读）
    private void broadcastFeedbackSyncToOtherAdmins(String excludeAdminId, String feedbackId) {
        List<String> adminIds = userService.getAdminUserIds();
        for (String adminId : adminIds) {
            if (!adminId.equals(excludeAdminId)) {
                TaskProgressWebSocket.sendFeedbackSync(adminId, feedbackId);
            }
        }
    }

    private void notifyAdmins(AnalysisFeedback feedback) {
        List<String> adminIds = userService.getAdminUserIds();
        notificationService.sendNotificationToUsers(
                adminIds,
                "FEEDBACK_NEW",
                "《" + getVideoTitle(feedback) + "》有新的反馈消息",
                quoteAndTruncate(extractLatestUserMessage(feedback)),
                feedback.getId(),
                "FEEDBACK",
                feedback.getId(),
                feedback.getVideoId(),
                "/admin/feedback"
        );
        // WebSocket 实时推送给所有在线管理员
        for (String adminId : adminIds) {
            TaskProgressWebSocket.sendFeedbackNew(adminId, feedback.getId());
        }
    }

    private String getVideoTitle(AnalysisFeedback feedback) {
        if (feedback.getVideoTitle() != null && !feedback.getVideoTitle().isEmpty()) {
            return feedback.getVideoTitle();
        }
        Video video = videoMapper.selectById(feedback.getVideoId());
        if (video != null && video.getTitle() != null && !video.getTitle().isEmpty()) {
            return video.getTitle();
        }
        return "未知视频";
    }

    private String extractLatestUserMessage(AnalysisFeedback feedback) {
        JSONArray messages = parseMessages(feedback.getContent());
        for (int i = messages.size() - 1; i >= 0; i--) {
            JSONObject msg = messages.getJSONObject(i);
            if (msg != null && "user".equals(msg.getString("role"))) {
                String text = msg.getString("text");
                return (text == null || text.isEmpty()) ? "用户发送了新消息" : text;
            }
        }
        return "用户发送了新消息";
    }

    private String quoteAndTruncate(String text) {
        if (text == null || text.isEmpty()) return "“新消息”";
        String clean = text.replaceAll("\\s+", " ").trim();
        int maxLen = 80;
        if (clean.length() > maxLen) {
            clean = clean.substring(0, maxLen) + "...";
        }
        return "“" + clean + "”";
    }

    private String buildAnalysisSnapshot(String taskId) {
        try {
            LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AnalysisResult::getTaskId, taskId);
            AnalysisResult result = analysisResultMapper.selectOne(wrapper);
            if (result == null) return null;

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("aiDescription", result.getAiDescription());
            snapshot.put("identityLabel", result.getIdentityLabel());
            snapshot.put("universityName", result.getUniversityName());
            snapshot.put("topicCategory", result.getTopicCategory());
            snapshot.put("topicSubCategory", result.getTopicSubCategory());
            snapshot.put("opinionRiskReason", result.getOpinionRiskReason());
            snapshot.put("actionSuggestion", result.getActionSuggestion());
            snapshot.put("actionDetail", result.getActionDetail());
            return JSON.toJSONString(snapshot);
        } catch (Exception e) {
            logger.warn("构建分析快照失败: taskId={}, error={}", taskId, e.getMessage());
            return null;
        }
    }

    private JSONArray parseMessages(String content) {
        if (content == null || content.isEmpty()) return new JSONArray();
        try {
            return JSON.parseArray(content);
        } catch (Exception e) {
            // 兼容旧的纯文本格式
            JSONArray arr = new JSONArray();
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("text", content);
            msg.put("time", "");
            arr.add(msg);
            return arr;
        }
    }

    private Page<FeedbackVO> convertPage(Page<AnalysisFeedback> entityPage) {
        Page<FeedbackVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    private Page<FeedbackVO> convertPageForAdmin(Page<AnalysisFeedback> entityPage, String adminId) {
        Page<FeedbackVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(e -> toVOForAdmin(e, adminId)).toList());
        return voPage;
    }

    private FeedbackVO toVO(AnalysisFeedback entity) {
        return toVOForAdmin(entity, null);
    }

    private FeedbackVO toVOForAdmin(AnalysisFeedback entity, String adminId) {
        User user = userMapper.selectById(entity.getUserId());
        User handler = entity.getHandlerId() != null ? userMapper.selectById(entity.getHandlerId()) : null;

        String videoTitle = entity.getVideoTitle();
        Video video = videoMapper.selectById(entity.getVideoId());
        boolean videoDeleted = (video == null);
        if (videoTitle == null && video != null) {
            videoTitle = video.getTitle();
        }

        Object snapshotObj = null;
        if (entity.getAnalysisSnapshot() != null && !entity.getAnalysisSnapshot().isEmpty()) {
            try {
                snapshotObj = JSON.parseObject(entity.getAnalysisSnapshot(), Map.class);
            } catch (Exception e) {
                snapshotObj = entity.getAnalysisSnapshot();
            }
        }

        // 查该管理员自己的未读数
        int adminUnread = 0;
        if (adminId != null) {
            Integer val = adminUnreadMapper.getUnread(entity.getId(), adminId);
            adminUnread = val != null ? val : 0;
        }

        return FeedbackVO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .username(user != null ? user.getName() : "未知用户")
                .taskId(entity.getTaskId())
                .videoId(entity.getVideoId())
                .videoTitle(videoTitle)
                .feedbackType(entity.getFeedbackType())
                .module(entity.getModule())
                .content(entity.getContent())
                .status(entity.getStatus())
                .handlerId(entity.getHandlerId())
                .handlerName(handler != null ? handler.getName() : null)
                .adminReply(entity.getAdminReply())
                .userUnread(entity.getUserUnread() == null ? 0 : entity.getUserUnread())
                .adminUnread(adminUnread)
                .analysisSnapshot(snapshotObj)
                .videoDeleted(videoDeleted)
                .gmtCreated(entity.getGmtCreated())
                .gmtModified(entity.getGmtModified())
                .build();
    }
}
