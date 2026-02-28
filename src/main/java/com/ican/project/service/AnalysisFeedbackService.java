package com.ican.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ican.project.model.dto.FeedbackCreateDTO;
import com.ican.project.model.entity.AnalysisFeedback;
import com.ican.project.model.vo.FeedbackVO;

public interface AnalysisFeedbackService extends IService<AnalysisFeedback> {

    /** 用户提交反馈 */
    FeedbackVO submitFeedback(String userId, FeedbackCreateDTO dto);

    /** 用户查看自己的反馈历史 */
    Page<FeedbackVO> getMyFeedbacks(String userId, int page, int size);

    /** 管理员查看反馈列表（分页），adminId 用于填充每人独立的未读数；onlyMine=true 时仅查看自己处理的反馈 */
    Page<FeedbackVO> getAdminFeedbackList(int page, int size, String status, String adminId, boolean onlyMine);

    /** 管理员锁定反馈（开始处理） */
    void lockFeedback(String feedbackId, String adminUserId);

    /** 用户查看自己对某视频的反馈 */
    FeedbackVO getMyFeedbackByVideo(String userId, String videoId);

    /** 管理员发送回复消息（纯聊天，不改状态） */
    void replyFeedback(String feedbackId, String adminUserId, String reply);

    /** 管理员关闭反馈（改状态为 RESOLVED 或 REJECTED） */
    void closeFeedback(String feedbackId, String adminUserId, String status);

    /** 根据ID获取单条反馈（用户本人或管理员可访问） */
    FeedbackVO getFeedbackById(String feedbackId);

    /** 清除未读数（用户清 userUnread；管理员清自己的 adminUnread） */
    void clearUnread(String feedbackId, boolean isAdmin, String userId);
}
