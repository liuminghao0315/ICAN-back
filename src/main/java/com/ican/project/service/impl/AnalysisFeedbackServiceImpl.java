package com.ican.project.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.AnalysisFeedbackMapper;
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

            // 只有重新打开时才通知管理员
            if (reopened) {
                notifyAdmins(userId, existing.getId());
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
                .gmtCreated(LocalDateTime.now())
                .gmtModified(LocalDateTime.now())
                .build();
        save(feedback);

        notifyAdmins(userId, feedback.getId());
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
    public Page<FeedbackVO> getAdminFeedbackList(int page, int size, String status) {
        Page<AnalysisFeedback> entityPage = new Page<>(page, size);
        LambdaQueryWrapper<AnalysisFeedback> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(AnalysisFeedback::getStatus, status);
        }
        wrapper.orderByDesc(AnalysisFeedback::getGmtCreated);
        feedbackMapper.selectPage(entityPage, wrapper);
        return convertPage(entityPage);
    }

    @Override
    public void lockFeedback(String feedbackId, String adminUserId) {
        int rows = feedbackMapper.lockFeedback(feedbackId, adminUserId);
        if (rows == 0) {
            throw new BusinessException("该反馈已被其他管理员处理或状态已变更");
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
        feedback.setGmtModified(LocalDateTime.now());
        updateById(feedback);

        notificationService.sendNotification(
                feedback.getUserId(),
                "FEEDBACK_REPLIED",
                "管理员回复了您的反馈",
                "您提交的分析反馈收到了新的管理员回复",
                feedbackId
        );
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

        feedback.setStatus(status);
        feedback.setGmtModified(LocalDateTime.now());
        updateById(feedback);

        String statusText = "RESOLVED".equals(status) ? "已解决" : "已驳回";
        notificationService.sendNotification(
                feedback.getUserId(),
                "FEEDBACK_REPLIED",
                "反馈处理结果：" + statusText,
                "您提交的分析反馈已被管理员标记为：" + statusText,
                feedbackId
        );
    }

    private void notifyAdmins(String userId, String feedbackId) {
        List<String> adminIds = userService.getAdminUserIds();
        User submitter = userMapper.selectById(userId);
        String submitterName = submitter != null ? submitter.getName() : "用户";
        notificationService.sendNotificationToUsers(
                adminIds,
                "FEEDBACK_NEW",
                "新的分析反馈",
                submitterName + " 提交了一条分析结果反馈，请及时处理",
                feedbackId
        );
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

    private FeedbackVO toVO(AnalysisFeedback entity) {
        User user = userMapper.selectById(entity.getUserId());
        User handler = entity.getHandlerId() != null ? userMapper.selectById(entity.getHandlerId()) : null;

        // videoTitle: 优先用快照字段，fallback 查 video 表
        String videoTitle = entity.getVideoTitle();
        Video video = videoMapper.selectById(entity.getVideoId());
        boolean videoDeleted = (video == null);
        if (videoTitle == null && video != null) {
            videoTitle = video.getTitle();
        }

        // 反序列化 analysisSnapshot
        Object snapshotObj = null;
        if (entity.getAnalysisSnapshot() != null && !entity.getAnalysisSnapshot().isEmpty()) {
            try {
                snapshotObj = JSON.parseObject(entity.getAnalysisSnapshot(), Map.class);
            } catch (Exception e) {
                snapshotObj = entity.getAnalysisSnapshot();
            }
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
                .analysisSnapshot(snapshotObj)
                .videoDeleted(videoDeleted)
                .gmtCreated(entity.getGmtCreated())
                .gmtModified(entity.getGmtModified())
                .build();
    }
}
