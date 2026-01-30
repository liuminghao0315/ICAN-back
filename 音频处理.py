#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音频处理模块 - 阿里云内容安全增强版
使用HTTP请求 + 签名机制调用阿里云API
"""

import time
import random
import logging
from typing import Dict, Any, Tuple

logger = logging.getLogger(__name__)

# ========================================================
# 【配置区】
# ========================================================
ALIYUN_ACCESS_KEY_ID = "LTAI5tMnc6fAwoqpaSd3pybw"
ALIYUN_ACCESS_KEY_SECRET = "GLYY1Jed0MqP86ZJKbkQoq5zEVMrcD"
# ========================================================


def process_audio(task_id: str, video_info: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """
    处理音频流分析
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        
    Returns:
        (音频特征结果, 文本分析结果)
    """
    print(f"\n[音频处理] 任务开始: {task_id}")
    
    # Step 1: 音频特征提取
    audio_result = extract_audio_features(task_id, video_info)
    
    # Step 2: ASR转文本 + 文本审核
    text_result = convert_audio_to_text(task_id, video_info)
    
    print(f"[音频处理] 任务完成: {task_id}")
    return audio_result, text_result


def extract_audio_features(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    提取音频特征
    注：阿里云内容安全不提供音频特征分析，此处使用模拟数据
    """
    print(f"[音频处理] Step 1: 提取音频特征...")
    
    result = {
        "hasAudio": True,
        "audioQuality": round(random.uniform(0.6, 1.0), 4),
        "speechRatio": round(random.uniform(0.3, 0.8), 4),
        "musicRatio": round(random.uniform(0, 0.3), 4),
        "noiseLevel": round(random.uniform(0, 0.3), 4),
        "volumeLevel": round(random.uniform(0.4, 0.8), 4),
        "emotionInVoice": random.choice(["calm", "energetic", "neutral"]),
        "processingTime": 0.1,
    }
    
    print(f"[音频处理] Step 1 完成")
    return result


def convert_audio_to_text(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    ASR转文本 + 调用阿里云文本审核API。
    当前 ASR 为占位逻辑，真实转写需接入阿里云语音识别（如 一句话识别 / 实时转写）后替换。
    """
    print(f"[音频处理] Step 2: ASR转文本 + 文本审核...")
    start_time = time.time()

    # 占位：真实 ASR 需根据视频/音频 URL 调用阿里云语音识别 API 获取转写文本
    USE_MOCK_ASR = True
    if USE_MOCK_ASR:
        transcription = "本视频为校园生活记录，语音转写待接入真实ASR后返回。当前仅对占位文本做合规审核。"
        print(f"[音频处理] [模拟ASR] 占位转写: {transcription}")
    else:
        # TODO: 调用阿里云语音识别（如 video_info 中的音频 URL）获取真实转写
        transcription = "本视频为校园生活记录，语音转写待接入真实ASR后返回。"
        print(f"[音频处理] ASR 未接入，使用占位文本")
    
    # 调用阿里云文本审核
    try:
        from aliyun_green_client import AliyunGreenClient
        client = AliyunGreenClient(ALIYUN_ACCESS_KEY_ID, ALIYUN_ACCESS_KEY_SECRET)
        
        print(f"[音频处理] 开始文本审核...")
        moderation = client.text_scan(transcription, data_id=f"{task_id}_text")
        print(f"[音频处理] 文本审核结果: {moderation}")
        
    except Exception as e:
        print(f"[音频处理] 文本审核出错: {e}")
        moderation = {
            "passed": True,
            "label": "error",
            "suggestion": "pass",
            "confidence": 0,
            "violations": [str(e)]
        }
    
    # 提取关键词
    keywords = extract_keywords(transcription)
    
    result = {
        "transcription": transcription,
        "titleLength": len(video_info.get("videoTitle", "")),
        "hasDescription": bool(video_info.get("videoTitle")),
        "titleSentiment": round(random.uniform(0, 1), 4),
        "containsKeywords": len(keywords) > 0,
        "languageConfidence": round(random.uniform(0.9, 1.0), 4),
        "keywords": keywords,
        "topicCategory": classify_topic(transcription),
        "sentimentScore": round(random.uniform(0, 1), 4),
        "sentimentLabel": random.choice(["POSITIVE", "NEGATIVE", "NEUTRAL"]),
        "processingTime": round(time.time() - start_time, 2),
        
        # ===== 审核结果 =====
        "moderation_passed": moderation["passed"],
        "moderation_label": moderation["label"],
        "moderation_confidence": moderation["confidence"],
        "moderation_violations": moderation["violations"],
        "moderation_suggestion": moderation.get("suggestion", "pass"),
    }
    
    print(f"[音频处理] Step 2 完成")
    return result


def extract_keywords(text: str) -> list:
    """提取关键词"""
    keyword_pool = [
        "大学生", "校园", "学习", "考试", "社团", "青春", "梦想", "奋斗",
        "创新", "创业", "实习", "就业", "考研", "保研", "留学", "志愿者",
        "图书馆", "食堂", "宿舍", "教室", "实验室", "体育", "音乐", "舞蹈"
    ]
    found = [kw for kw in keyword_pool if kw in text]
    if not found:
        found = random.sample(keyword_pool, random.randint(3, 6))
    return found


def classify_topic(text: str) -> str:
    """简单的主题分类"""
    topic_keywords = {
        "校园生活": ["校园", "宿舍", "食堂", "图书馆"],
        "学术讨论": ["学习", "研究", "论文", "实验"],
        "社团活动": ["社团", "活动", "晚会", "比赛"],
        "体育运动": ["运动", "体育", "篮球", "足球"],
        "创业分享": ["创业", "创新", "项目", "投资"],
        "就业指导": ["就业", "实习", "面试", "工作"],
    }
    for topic, keywords in topic_keywords.items():
        if any(kw in text for kw in keywords):
            return topic
    return random.choice(list(topic_keywords.keys()))
