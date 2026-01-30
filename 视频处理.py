#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频处理模块
负责处理视觉流分析
开发者：潘嘉乐
"""

import time
import random
import logging
import requests
from typing import Dict, Any

# 配置日志
logger = logging.getLogger(__name__)


def download_video(video_url: str, task_id: str) -> str:
    """
    验证视频URL或下载视频
    注意：ffmpeg可以直接读取http URL，所以这里直接返回URL即可
    
    Args:
        video_url: 视频URL
        task_id: 任务ID
        
    Returns:
        视频URL或本地路径
    """
    try:
        # 验证URL是否可访问（HEAD请求）
        response = requests.head(video_url, timeout=10)
        if response.status_code == 200:
            logger.info(f"[任务 {task_id}] 视频URL验证成功: {video_url}")
            return video_url
        else:
            logger.warning(f"[任务 {task_id}] 视频URL返回状态码: {response.status_code}")
            return video_url
    except Exception as e:
        logger.error(f"[任务 {task_id}] 视频URL验证失败: {e}")
        # 即使验证失败，也返回URL，让ffmpeg尝试读取
        return video_url


def process_video(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    处理视觉流分析
    
    Args:
        task_id: 任务ID
        video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
        
    Returns:
        视觉流分析结果（JSON格式的字典）
    """
    logger.info(f"[任务 {task_id}] [视频处理模块] 开始处理视觉流...")
    
    try:
        # 获取视频URL并打印
        video_url = video_info.get("videoUrl")
        logger.info(f"[任务 {task_id}] [视频处理模块] ========== 视频URL: {video_url} ==========")
        
        # 验证或下载视频
        video_path = download_video(video_url, task_id) if video_url else None
        
        # 模拟处理时间（3-8秒）
        process_time = random.uniform(3, 8)
        time.sleep(process_time)
        
        # 生成视觉流分析结果
        result = {
            "videoPath": video_path,  # 新增：供音频处理使用
            "duration": video_info.get("videoDuration", 60.0),
            "width": 1920,
            "height": 1080,
            "fps": random.randint(24, 60),
            "sceneType": random.choice(["教室", "图书馆", "操场", "宿舍", "食堂", "实验室", "报告厅", "校园户外"]),
            "sceneConfidence": round(random.uniform(0.7, 1.0), 4),
            "faceCount": random.randint(0, 10),
            "hasPerson": random.choice([True, False]),
            "qualityScore": round(random.uniform(0.5, 1.0), 4),
            "brightness": round(random.uniform(0.3, 0.7), 4),
            "clarity": round(random.uniform(0.6, 1.0), 4),
            "processingTime": round(process_time, 2)
        }
        
        logger.info(f"[任务 {task_id}] [视频处理模块] 视觉流处理完成，处理时间: {process_time:.2f}秒")
        return result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [视频处理模块] 处理失败: {e}", exc_info=True)
        raise

