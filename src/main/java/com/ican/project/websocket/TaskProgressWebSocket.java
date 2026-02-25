package com.ican.project.websocket;

import com.alibaba.fastjson2.JSON;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务进度WebSocket端点
 * 用于向前端推送任务处理进度
 */
@Component
@ServerEndpoint("/ws/task-progress/{userId}")
public class TaskProgressWebSocket {
    
    private static final Logger logger = LoggerFactory.getLogger(TaskProgressWebSocket.class);
    
    /**
     * 存储所有连接的Session
     * key: userId
     * value: Session
     */
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    
    /**
     * 连接建立时调用
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        SESSIONS.put(userId, session);
        logger.info("WebSocket连接建立: userId={}, sessionId={}", userId, session.getId());
        
        // 发送连接成功消息
        sendMessage(userId, createMessage("connected", "连接成功", null));
    }
    
    /**
     * 连接关闭时调用
     */
    @OnClose
    public void onClose(Session session, @PathParam("userId") String userId) {
        SESSIONS.remove(userId);
        logger.info("WebSocket连接关闭: userId={}", userId);
    }
    
    /**
     * 收到客户端消息时调用
     */
    @OnMessage
    public void onMessage(String message, Session session, @PathParam("userId") String userId) {
        logger.debug("收到WebSocket消息: userId={}, message={}", userId, message);
        
        // 心跳响应
        if ("ping".equals(message)) {
            sendMessage(userId, "pong");
        }
    }
    
    /**
     * 发生错误时调用
     */
    @OnError
    public void onError(Session session, Throwable error, @PathParam("userId") String userId) {
        logger.error("WebSocket错误: userId={}, error={}", userId, error.getMessage());
        SESSIONS.remove(userId);
    }
    
    /**
     * 向指定用户发送消息
     * @param userId 用户ID
     * @param message 消息内容
     */
    public static void sendMessage(String userId, String message) {
        logger.info("尝试发送WebSocket消息: userId={}, 当前在线用户={}", userId, SESSIONS.keySet());
        Session session = SESSIONS.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                logger.info("WebSocket消息发送成功: userId={}", userId);
            } catch (IOException e) {
                logger.error("发送WebSocket消息失败: userId={}, error={}", userId, e.getMessage());
            }
        } else {
            logger.warn("WebSocket发送失败: 用户 {} 不在线或会话已关闭", userId);
        }
    }
    
    /**
     * 向指定用户发送任务进度更新
     * @param userId   用户ID
     * @param taskId   任务ID
     * @param videoId  视频ID
     * @param status   任务状态
     * @param progress 进度（0-100）
     * @param message  消息
     */
    public static void sendTaskProgress(String userId, String taskId, String videoId,
                                        String status, Integer progress, String message) {
        sendTaskProgress(userId, taskId, videoId, status, progress, message, null, null);
    }

    /**
     * 向指定用户发送任务进度更新（含阶段标识、标题、视频URL、缩略图URL）
     * @param stage        阶段标识：FETCHING_TITLE / DOWNLOADING / PENDING / PROCESSING / COMPLETED / FAILED
     * @param title        视频标题（元数据阶段获取后填入，其余阶段传 null）
     * @param videoUrl     视频可访问URL（下载完成后填入）
     * @param thumbnailUrl 缩略图URL（下载完成后填入）
     */
    public static void sendTaskProgress(String userId, String taskId, String videoId,
                                        String status, Integer progress, String message,
                                        String stage, String title, String videoUrl, String thumbnailUrl) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("taskId", taskId);
        data.put("videoId", videoId != null ? videoId : "");
        data.put("status", status);
        data.put("progress", progress != null ? progress : 0);
        data.put("message", message != null ? message : "");
        if (stage != null)         data.put("stage", stage);
        if (title != null)         data.put("title", title);
        if (videoUrl != null)      data.put("videoUrl", videoUrl);
        if (thumbnailUrl != null)  data.put("thumbnailUrl", thumbnailUrl);
        sendMessage(userId, createMessage("task_progress", "任务进度更新", data));
    }

    /**
     * 向指定用户发送任务进度更新（含阶段标识、标题、视频URL）
     * @param stage    阶段标识：FETCHING_TITLE / DOWNLOADING / PENDING / PROCESSING / COMPLETED / FAILED
     * @param title    视频标题（元数据阶段获取后填入，其余阶段传 null）
     * @param videoUrl 视频可访问URL（下载完成后填入，用于前端截帧生成缩略图）
     */
    public static void sendTaskProgress(String userId, String taskId, String videoId,
                                        String status, Integer progress, String message,
                                        String stage, String title, String videoUrl) {
        sendTaskProgress(userId, taskId, videoId, status, progress, message, stage, title, videoUrl, null);
    }

    /**
     * 向指定用户发送任务进度更新（含阶段标识和标题）
     * @param stage    阶段标识：FETCHING_TITLE / DOWNLOADING / PENDING / PROCESSING / COMPLETED / FAILED
     * @param title    视频标题（元数据阶段获取后填入，其余阶段传 null）
     */
    public static void sendTaskProgress(String userId, String taskId, String videoId,
                                        String status, Integer progress, String message,
                                        String stage, String title) {
        sendTaskProgress(userId, taskId, videoId, status, progress, message, stage, title, null);
    }
    
    /**
     * 向指定用户发送任务完成通知
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param videoId 视频ID
     * @param resultId 结果ID
     */
    public static void sendTaskCompleted(String userId, String taskId, String videoId, String resultId) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("taskId", taskId);
        data.put("videoId", videoId != null ? videoId : "");
        data.put("resultId", resultId != null ? resultId : "");
        sendMessage(userId, createMessage("task_completed", "任务已完成", data));
    }
    
    /**
     * 向指定用户发送任务失败通知
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param videoId 视频ID
     * @param errorMessage 错误信息
     */
    public static void sendTaskFailed(String userId, String taskId, String videoId, String errorMessage) {
        sendTaskFailed(userId, taskId, videoId, errorMessage, "ANALYSIS_FAILED");
    }

    /**
     * 向指定用户发送任务失败通知（含失败类型）
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param videoId 视频ID
     * @param errorMessage 错误信息
     * @param failureType 失败类型：DOWNLOAD_FAILED（下载失败，无文件）/ ANALYSIS_FAILED（分析失败，有文件）
     */
    public static void sendTaskFailed(String userId, String taskId, String videoId, String errorMessage, String failureType) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("taskId", taskId);
        data.put("videoId", videoId != null ? videoId : "");
        data.put("errorMessage", errorMessage != null ? errorMessage : "未知错误");
        data.put("failureType", failureType != null ? failureType : "ANALYSIS_FAILED");
        sendMessage(userId, createMessage("task_failed", "任务失败", data));
    }
    
    /**
     * 广播消息给所有在线用户
     * @param message 消息内容
     */
    public static void broadcast(String message) {
        SESSIONS.forEach((userId, session) -> {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    logger.error("广播WebSocket消息失败: userId={}, error={}", userId, e.getMessage());
                }
            }
        });
    }
    
    /**
     * 获取在线用户数量
     */
    public static int getOnlineCount() {
        return SESSIONS.size();
    }
    
    /**
     * 检查用户是否在线
     */
    public static boolean isUserOnline(String userId) {
        Session session = SESSIONS.get(userId);
        return session != null && session.isOpen();
    }
    
    /**
     * 创建标准消息格式
     */
    private static String createMessage(String type, String message, Object data) {
        Map<String, Object> msg = new java.util.HashMap<>();
        msg.put("type", type);
        msg.put("message", message);
        msg.put("timestamp", System.currentTimeMillis());
        if (data != null) {
            msg.put("data", data);
        }
        return JSON.toJSONString(msg);
    }
}

