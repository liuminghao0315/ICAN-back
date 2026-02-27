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

    /** 管理员查看所有反馈（分页） */
    Page<FeedbackVO> getAdminFeedbackList(int page, int size, String status);

    /** 管理员锁定反馈（开始处理） */
    void lockFeedback(String feedbackId, String adminUserId);

    /** 用户查看自己对某视频的反馈 */
    FeedbackVO getMyFeedbackByVideo(String userId, String videoId);

    /** 管理员发送回复消息（纯聊天，不改状态） */
    void replyFeedback(String feedbackId, String adminUserId, String reply);

    /** 管理员关闭反馈（改状态为 RESOLVED 或 REJECTED） */
    void closeFeedback(String feedbackId, String adminUserId, String status);
}
