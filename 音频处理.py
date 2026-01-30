#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音频处理模块
负责处理音频流分析（包含音频特征提取和ASR转文本）
开发者：展鑫鑫
"""

import time
import random
import logging
import os
import tempfile
import ffmpeg
import whisper
from typing import Dict, Any, Tuple

# 配置日志
logger = logging.getLogger(__name__)

# 全局Whisper模型（避免重复加载）
_whisper_model = None


def get_whisper_model():
    """
    获取或加载Whisper模型（单例模式）
    
    Returns:
        Whisper模型实例
    """
    global _whisper_model
    if _whisper_model is None:
        logger.info("正在加载Whisper模型（base）...")
        try:
            # 使用base模型，平衡速度和准确率
            _whisper_model = whisper.load_model("base")
            logger.info("Whisper模型加载成功")
        except Exception as e:
            logger.error(f"Whisper模型加载失败: {e}")
            raise
    return _whisper_model


def extract_audio_from_video(video_source: str, task_id: str) -> str:
    """
    使用ffmpeg从视频中提取音频
    
    Args:
        video_source: 视频URL或本地路径
        task_id: 任务ID
        
    Returns:
        音频文件路径
    """
    # 保存到当前目录的audio_files文件夹（便于查看和验证）
    audio_dir = "audio_files"
    if not os.path.exists(audio_dir):
        os.makedirs(audio_dir)
    audio_path = os.path.join(audio_dir, f"audio_{task_id}.wav")
    
    try:
        logger.info(f"[任务 {task_id}] [音频处理模块] 开始提取音频...")
        
        # 使用ffmpeg提取音频
        stream = ffmpeg.input(video_source)
        stream = ffmpeg.output(
            stream, 
            audio_path,
            acodec='pcm_s16le',  # WAV格式，16位PCM
            ac=1,  # 单声道
            ar='16000'  # 16kHz采样率（Whisper推荐）
        )
        ffmpeg.run(stream, overwrite_output=True, capture_stderr=True, quiet=True)
        
        logger.info(f"[任务 {task_id}] [音频处理模块] ========== 音频获取成功 ==========")
        return audio_path
        
    except ffmpeg.Error as e:
        error_msg = e.stderr.decode() if e.stderr else str(e)
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频提取失败: {error_msg}")
        raise
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频提取失败: {e}")
        raise


def asr_recognize(audio_path: str, task_id: str) -> str:
    """
    使用Whisper进行ASR语音识别
    
    Args:
        audio_path: 音频文件路径
        task_id: 任务ID
        
    Returns:
        识别的文本内容
    """
    try:
        logger.info(f"[任务 {task_id}] [音频处理模块] 开始ASR识别...")
        
        # 获取Whisper模型
        model = get_whisper_model()
        
        # 转录音频
        result = model.transcribe(
            audio_path,
            language="zh",  # 中文
            fp16=False  # CPU模式不使用FP16
        )
        
        # 获取识别的文本
        full_text = result["text"].strip()
        
        if not full_text:
            full_text = "（未检测到语音内容）"
        
        logger.info(f"[任务 {task_id}] [音频处理模块] ========== ASR识别文本: {full_text} ==========")
        
        return full_text
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] ASR识别失败: {e}")
        return "（ASR识别失败）"


def process_audio(task_id: str, video_info: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """
    处理音频流分析（包含两个步骤）
    
    Args:
        task_id: 任务ID
        video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
        
    Returns:
        Tuple[音频特征结果, 文本分析结果]
        第一个字典是音频特征提取结果（moduleType: "audio"）
        第二个字典是ASR转文本结果（moduleType: "text"）
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] 开始处理音频流...")
    
    audio_path = None
    try:
        # 获取视频URL
        video_url = video_info.get("videoUrl")
        if not video_url:
            raise ValueError("视频URL不存在")
        
        # Step 1: 从视频中提取音频
        audio_path = extract_audio_from_video(video_url, task_id)
        
        # Step 2: 音频特征提取
        audio_result = extract_audio_features(task_id, video_info)
        
        # Step 3: ASR转文本
        transcription = asr_recognize(audio_path, task_id)
        text_result = convert_audio_to_text(task_id, video_info, transcription)
        
        logger.info(f"[任务 {task_id}] [音频处理模块] 音频流处理完成")
        return audio_result, text_result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 处理失败: {e}", exc_info=True)
        raise
    finally:
        # 保留音频文件用于验证（如需自动清理，取消下面的注释）
        # if audio_path and os.path.exists(audio_path):
        #     try:
        #         os.remove(audio_path)
        #         logger.debug(f"[任务 {task_id}] [音频处理模块] 已清理临时音频文件: {audio_path}")
        #     except Exception as e:
        #         logger.warning(f"[任务 {task_id}] [音频处理模块] 清理临时文件失败: {e}")
        pass


def extract_audio_features(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    提取音频特征（Step 1）
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        
    Returns:
        音频特征分析结果
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 开始提取音频特征...")
    
    # 模拟处理时间（2-5秒）
    process_time = random.uniform(2, 5)
    time.sleep(process_time)
    
    # 生成音频特征分析结果
    result = {
        "hasAudio": True,
        "audioQuality": round(random.uniform(0.6, 1.0), 4),
        "speechRatio": round(random.uniform(0.3, 0.8), 4),
        "musicRatio": round(random.uniform(0, 0.3), 4),
        "noiseLevel": round(random.uniform(0, 0.3), 4),
        "volumeLevel": round(random.uniform(0.4, 0.8), 4),
        "emotionInVoice": random.choice(["calm", "energetic", "neutral"]),
        "processingTime": round(process_time, 2)
    }
    
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 音频特征提取完成，处理时间: {process_time:.2f}秒")
    return result


def convert_audio_to_text(task_id: str, video_info: Dict[str, Any], transcription: str) -> Dict[str, Any]:
    """
    音频转文本（ASR）（Step 2）
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        transcription: ASR识别的文本内容
        
    Returns:
        文本分析结果
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 2: 开始文本分析...")
    
    # 模拟处理时间（1-3秒）
    process_time = random.uniform(1, 3)
    time.sleep(process_time)
    
    # 生成文本分析结果
    keywords = random.sample([
        "大学生", "校园", "学习", "考试", "社团", "青春", "梦想", "奋斗",
        "创新", "创业", "实习", "就业", "考研", "保研", "留学", "志愿者"
    ], random.randint(3, 7))
    
    result = {
        "transcription": transcription,  # 使用真实的ASR识别结果
        "titleLength": len(video_info.get("videoTitle", "")),
        "hasDescription": bool(video_info.get("videoTitle")),
        "titleSentiment": round(random.uniform(-1, 1), 4),
        "containsKeywords": random.choice([True, False]),
        "languageConfidence": round(random.uniform(0.9, 1.0), 4),
        "keywords": keywords,
        "topicCategory": random.choice([
            "校园生活", "学术讨论", "社团活动", "体育运动", "艺术表演",
            "科技创新", "创业分享", "心理健康", "就业指导", "社会实践"
        ]),
        "sentimentScore": round(random.uniform(-1, 1), 4),
        "sentimentLabel": random.choice(["POSITIVE", "NEGATIVE", "NEUTRAL"]),
        "processingTime": round(process_time, 2)
    }
    
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 2: 文本分析完成，处理时间: {process_time:.2f}秒")
    return result

