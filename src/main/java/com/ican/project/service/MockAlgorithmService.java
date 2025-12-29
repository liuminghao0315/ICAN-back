package com.ican.project.service;

/**
 * Mock算法服务接口
 * 模拟真实的算法分析服务，用于开发和测试阶段
 */
public interface MockAlgorithmService {
    
    /**
     * 启动Mock算法处理器
     * 持续监听并处理待分析的任务
     */
    void startProcessor();
    
    /**
     * 停止Mock算法处理器
     */
    void stopProcessor();
    
    /**
     * 手动触发处理一个待处理任务
     * @return 是否有任务被处理
     */
    boolean processOneTask();
    
    /**
     * 获取处理器运行状态
     * @return 是否正在运行
     */
    boolean isRunning();
    
    /**
     * 应用启动完成后的回调
     * 用于在应用完全启动后启动处理器
     */
    void onApplicationReady();
}

