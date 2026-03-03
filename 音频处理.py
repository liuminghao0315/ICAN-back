#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音频处理模块
负责处理音频流分析（包含音频特征提取和ASR转文本）
开发者：展鑫鑫
"""

import time
import logging
import os
import tempfile
import ffmpeg
import whisper
import jieba
import jieba.analyse
from snownlp import SnowNLP
import librosa
import numpy as np
from typing import Dict, Any, Tuple, List, Optional

# 配置日志
logger = logging.getLogger(__name__)

# 全局Whisper模型（避免重复加载）
_whisper_model = None
_audio_ov_init_attempted = False
_audio_ov_pipeline = None


def infer_voice_emotion_from_features(audio_quality: float, volume_level: float, noise_level: float) -> str:
    """
    基于可解释规则推断语音情绪标签（无专用情绪模型时的兜底方案）。
    """
    aq = min(1.0, max(0.0, float(audio_quality)))
    vl = min(1.0, max(0.0, float(volume_level)))
    nl = min(1.0, max(0.0, float(noise_level)))

    if vl >= 0.75 and nl >= 0.18:
        return "tense"
    if vl >= 0.68:
        return "energetic"
    if vl <= 0.35 and aq >= 0.55:
        return "calm"
    return "neutral"


def build_audio_open_vocab_prompts(word_packs: Optional[list] = None) -> List[str]:
    """构建音频开放词汇提示词（无训练方案）。"""
    base_prompts = [
        "shouting", "screaming", "arguing", "fighting", "crowd noise",
        "glass breaking", "explosion", "sirens", "alarm", "crying"
    ]

    extra = []
    for pack in word_packs or []:
        words = pack.get("words", []) if isinstance(pack, dict) else []
        for w in words:
            if isinstance(w, str):
                t = w.strip()
            elif isinstance(w, dict):
                t = str(w.get("text", "")).strip()
            else:
                t = ""
            if t:
                extra.append(t)

    prompts = []
    seen = set()
    for t in base_prompts + extra:
        k = t.lower()
        if k not in seen:
            seen.add(k)
            prompts.append(t)
    return prompts[:50]


def map_audio_open_vocab_to_risk(audio_hits: List[dict]) -> Dict[str, Any]:
    """把音频开放词汇命中映射成风险分（可解释）。"""
    if not audio_hits:
        return {"riskScore": 0.0, "riskFactors": []}

    weights = {
        "shouting": 0.35,
        "screaming": 0.45,
        "arguing": 0.35,
        "fighting": 0.5,
        "争吵": 0.35,
        "喊叫": 0.35,
        "爆炸": 0.6,
        "alarm": 0.25,
        "sirens": 0.25,
    }

    total = 0.0
    factors = []
    for hit in audio_hits:
        label = str(hit.get("label", "")).lower()
        conf = float(hit.get("confidence", 0.0))
        matched = None
        w = 0.0
        for k, v in weights.items():
            if k.lower() in label:
                matched = k
                w = v
                break
        if w <= 0:
            continue
        score = min(1.0, w * max(0.0, min(1.0, conf)) * 2)
        total += score
        factors.append({
            "label": hit.get("label", ""),
            "confidence": round(conf, 3),
            "matched": matched,
            "score": round(score, 3),
        })

    return {"riskScore": round(min(1.0, total), 3), "riskFactors": factors}


def _get_audio_open_vocab_pipeline():
    """可选加载 transformers 音频零样本分类器（失败自动降级）。"""
    global _audio_ov_init_attempted, _audio_ov_pipeline
    if _audio_ov_pipeline is not None:
        return _audio_ov_pipeline
    if _audio_ov_init_attempted:
        return None

    _audio_ov_init_attempted = True
    try:
        from transformers import pipeline  # type: ignore
        _audio_ov_pipeline = pipeline("zero-shot-audio-classification", model="laion/clap-htsat-unfused")
        logger.info("音频开放词汇模型加载成功（CLAP）")
    except Exception as e:
        logger.warning(f"音频开放词汇模型不可用，降级规则分析: {e}")
        _audio_ov_pipeline = None

    return _audio_ov_pipeline


def detect_audio_events_optional(audio_path: str, prompts: List[str]) -> List[dict]:
    """可选音频开放词汇识别。"""
    clf = _get_audio_open_vocab_pipeline()
    if clf is None:
        return []
    try:
        results = clf(audio_path, candidate_labels=prompts)
        labels = results.get("labels", [])
        scores = results.get("scores", [])
        hits = []
        for i in range(min(5, len(labels), len(scores))):
            if float(scores[i]) >= 0.2:
                hits.append({"label": labels[i], "confidence": round(float(scores[i]), 4)})
        return hits
    except Exception as e:
        logger.warning(f"音频开放词汇推理失败，降级忽略: {e}")
        return []


def _extract_pack_words(custom_word_packs: Optional[list]) -> List[dict]:
    """
    统一解析风险词库包，支持两种格式：
      1) { words: ["词1", "词2"] }
      2) { words: [{text: "词1", category: "...", riskLevel: "..."}] }
    """
    if not custom_word_packs:
        return []

    normalized = []
    for pack in custom_word_packs:
        words = pack.get("words", []) if isinstance(pack, dict) else []
        for w in words:
            if isinstance(w, str):
                text = w.strip()
                if text:
                    normalized.append({
                        "text": text,
                        "category": "自定义词库",
                        "riskLevel": "medium",
                        "packName": pack.get("name", "自定义词库") if isinstance(pack, dict) else "自定义词库"
                    })
            elif isinstance(w, dict):
                text = str(w.get("text", "")).strip()
                if text:
                    normalized.append({
                        "text": text,
                        "category": w.get("category", "自定义词库"),
                        "riskLevel": w.get("riskLevel", "medium"),
                        "packName": pack.get("name", "自定义词库") if isinstance(pack, dict) else "自定义词库"
                    })
    return normalized


def build_hotwords_from_word_packs(custom_word_packs: Optional[list], max_words: int = 80) -> List[str]:
    """从前端挂载词库中提取 Whisper 热词。"""
    words = _extract_pack_words(custom_word_packs)
    # 高风险词优先，其次按词长排序（长词更有业务价值）
    risk_weight = {"high": 0, "medium": 1, "low": 2}
    words.sort(key=lambda x: (risk_weight.get(str(x.get("riskLevel", "medium")).lower(), 1), -len(x.get("text", ""))))

    dedup = []
    seen = set()
    for w in words:
        text = w["text"]
        if text not in seen:
            seen.add(text)
            dedup.append(text)
        if len(dedup) >= max_words:
            break
    return dedup


def build_whisper_initial_prompt(hotwords: List[str]) -> str:
    """构造 Whisper initial_prompt，引导识别业务词汇。"""
    if not hotwords:
        return ""
    joined = "、".join(hotwords)
    return f"以下词汇在音频中可能出现，请尽量准确识别：{joined}。"


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


def asr_recognize(audio_path: str, task_id: str, custom_word_packs: Optional[list] = None) -> str:
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
        
        # 热词提示（来自前端风险词库管理）
        hotwords = build_hotwords_from_word_packs(custom_word_packs)
        initial_prompt = build_whisper_initial_prompt(hotwords)

        # 转录音频
        result = model.transcribe(
            audio_path,
            language="zh",  # 中文
            fp16=False,  # CPU模式不使用FP16
            initial_prompt=initial_prompt if initial_prompt else None,
        )
        
        # 获取识别的文本
        full_text = result["text"].strip()
        
        if not full_text:
            full_text = "（未检测到语音内容）"
        
        logger.info(f"[任务 {task_id}] [音频处理模块] ========== ASR识别文本: {full_text} ==========")
        if hotwords:
            logger.info(f"[任务 {task_id}] [音频处理模块] 已应用风险词库热词 {len(hotwords)} 个")
        
        return full_text
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] ASR识别失败: {e}")
        return "（ASR识别失败）"


def generate_audio_effect_events(duration: float, audio_quality: float, task_id: str) -> list:
    """
    生成声学事件流（AudioEffectEvent类型） - 时间点混合分布
    
    Args:
        duration: 视频时长
        audio_quality: 音频质量
        task_id: 任务ID
        
    Returns:
        声学事件列表
    """
    audio_events = []
    event_id = 1

    duration_i = max(1, int(duration))
    event_count = min(8, max(3, duration_i // 12))
    step = max(5, duration_i // (event_count + 1))
    aq = min(1.0, max(0.0, float(audio_quality)))

    for i in range(event_count):
        start_time = min(duration_i - 1, (i + 1) * step)
        event_len = 2 + int((1 - aq) * 3)
        end_time = min(duration_i, start_time + event_len)
        intensity = min(1.0, 0.45 + (1 - aq) * 0.4 + (i % 3) * 0.05)
        risk_score = int(min(95, max(35, intensity * 100)))

        if intensity >= 0.8:
            desc = "检测到异常高能量声学片段"
        elif intensity >= 0.65:
            desc = "检测到音量波动明显"
        else:
            desc = "检测到常规语音/环境音变化"

        audio_events.append({
            "id": f"audio-{event_id:03d}",
            "modality": "audio-effect",
            "startTime": start_time,
            "endTime": end_time,
            "riskScore": risk_score,
            "description": desc,
            "intensity": round(intensity, 2),
            "confidence": int(min(98, max(70, 78 + aq * 18)))
        })
        event_id += 1
    
    logger.info(f"[任务 {task_id}] [音频处理模块] 生成 {len(audio_events)} 个声学事件")
    return audio_events


def generate_audio_emotions(duration: float, speech_ratio: float) -> list:
    """
    生成音频情绪时间序列（基于5秒粒度）
    
    Args:
        duration: 视频时长
        speech_ratio: 语音比例
        
    Returns:
        音频情绪列表（索引对应时间段）
    """
    time_granularity = 5
    num_segments = int(duration / time_granularity) + 1
    audio_emotions = []
    
    sr = min(1.0, max(0.0, float(speech_ratio)))
    for i in range(num_segments):
        pos = i / max(1, num_segments - 1)
        peak = 1 - abs(pos - 0.55) * 1.8
        peak = max(0.0, min(1.0, peak))
        intensity = 0.2 + sr * 0.35 + peak * 0.25
        intensity = max(0.1, min(0.98, intensity))

        if intensity >= 0.8:
            reason = "语音强度较高，情绪张力明显"
        elif intensity >= 0.55:
            reason = "语音表达中等偏强"
        else:
            reason = "语音表达平稳"
        
        audio_emotions.append({
            "intensity": round(intensity, 2),
            "reason": reason
        })
    
    return audio_emotions


def calculate_audio_risk_scores(audio_quality: float, speech_ratio: float, volume_level: float) -> dict:
    """
    计算音频维度的6个风险分数
    
    Args:
        audio_quality: 音频质量
        speech_ratio: 语音比例
        volume_level: 音量水平
        
    Returns:
        6个维度的音频分数（0-100）
    """
    # 去随机化：使用可解释、可复现实用规则（无算法组专用模型时的稳定方案）
    aq = min(1.0, max(0.0, float(audio_quality)))
    sr = min(1.0, max(0.0, float(speech_ratio)))
    vl = min(1.0, max(0.0, float(volume_level)))

    # 1. 身份置信度：语音占比 + 音频质量越高，越容易判断身份线索
    identity_score = int(35 + sr * 45 + aq * 20)

    # 2. 学校关联度：音频本身对“高校关联”能力有限，给中等且随语音质量变化
    university_score = int(40 + sr * 25 + aq * 20)

    # 3. 负面情感度：音量偏高、语速（语音占比）偏高通常意味着情绪更强
    attitude_score = int(25 + vl * 45 + sr * 20)

    # 4. 主题分数：语音可懂度（质量+语音占比）越高，主题识别越稳
    topic_score = int(30 + aq * 35 + sr * 30)

    # 5. 舆论风险：高音量是主要信号，语音占比辅助
    opinion_risk_score = int(20 + vl * 55 + sr * 20)

    # 6. 处置紧迫度：由态度与舆论风险共同决定
    action_score = int(opinion_risk_score * 0.6 + attitude_score * 0.4)
    
    return {
        "identity": min(100, max(0, identity_score)),
        "university": min(100, max(0, university_score)),
        "topic": min(100, max(0, topic_score)),
        "attitude": min(100, max(0, attitude_score)),
        "opinionRisk": min(100, max(0, opinion_risk_score)),
        "action": min(100, max(0, action_score))
    }


def process_audio(task_id: str, video_info: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """
    处理音频流分析（包含两个步骤）
    适配新的前端数据结构
    
    Args:
        task_id: 任务ID
        video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
        
    Returns:
        Tuple[音频特征结果, 文本分析结果]
        第一个字典是音频模块结果（声学事件、音频情绪、音频特征分数）
        第二个字典是文本模块结果（语音事件、文本风险、文本特征分数、初步证据）
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] 开始处理音频流...")
    
    audio_path = None
    try:
        # 获取内网URL用于ffmpeg快速处理
        video_url_internal = video_info.get("videoUrlInternal") or video_info.get("videoUrl")
        if not video_url_internal:
            raise ValueError("视频URL不存在")
        
        duration = video_info.get("videoDuration", 60.0)
        
        # Step 1: 从视频中提取音频
        audio_path = extract_audio_from_video(video_url_internal, task_id)
        
        # Step 2: 音频特征提取
        audio_features = extract_audio_features(task_id, video_info, audio_path)
        
        # Step 3: ASR转文本（接入前端风险词库热词提示）
        custom_word_packs = video_info.get("wordPacks")
        transcription = asr_recognize(audio_path, task_id, custom_word_packs=custom_word_packs)

        # Step 3.5: 音频开放词汇识别（可选：CLAP/transformers）
        audio_ov_prompts = build_audio_open_vocab_prompts(custom_word_packs)
        audio_ov_hits = detect_audio_events_optional(audio_path, audio_ov_prompts)
        audio_ov_risk = map_audio_open_vocab_to_risk(audio_ov_hits)
        
        # ========== 构建音频模块结果（Step 2: Audio → 50%） ==========
        
        # 1. 生成声学事件流
        audio_effect_events = generate_audio_effect_events(
            duration,
            audio_features.get("audioQuality", 0.7),
            task_id
        )
        
        # 2. 生成音频情绪时间序列
        audio_emotions = generate_audio_emotions(
            duration,
            audio_features.get("speechRatio", 0.5)
        )
        
        # 3. 计算音频维度的特征分数
        audio_risk_scores = calculate_audio_risk_scores(
            audio_features.get("audioQuality", 0.7),
            audio_features.get("speechRatio", 0.5),
            audio_features.get("volumeLevel", 0.6)
        )

        # 将开放词汇音频风险注入分数（可解释增强）
        ov_bonus = int(audio_ov_risk.get("riskScore", 0.0) * 25)
        audio_risk_scores["opinionRisk"] = min(100, audio_risk_scores["opinionRisk"] + ov_bonus)
        audio_risk_scores["action"] = min(100, audio_risk_scores["action"] + int(ov_bonus * 0.8))
        
        audio_result = {
            # 声学事件流
            "audioEffectEvents": audio_effect_events,
            
            # 时间轴-音频情绪
            "audioEmotions": audio_emotions,
            
            # 特征数据（用于后续融合）
            "features": {
                "audioQuality": audio_features.get("audioQuality", 0.7),
                "speechRatio": audio_features.get("speechRatio", 0.5),
                "musicRatio": audio_features.get("musicRatio", 0.1),
                "noiseLevel": audio_features.get("noiseLevel", 0.2),
                "volumeLevel": audio_features.get("volumeLevel", 0.6),
                "emotionInVoice": audio_features.get("emotionInVoice", "neutral"),
                
                # 6个维度的音频风险分数
                "audioRiskScores": audio_risk_scores
            },

            "openVocab": {
                "engine": "CLAP(optional)",
                "prompts": audio_ov_prompts,
                "detections": audio_ov_hits,
                "risk": audio_ov_risk,
            },
            
            "processingTime": audio_features.get("processingTime", 0)
        }
        
        # ========== 构建文本模块结果（Step 3: Text → 75%） ==========
        text_analysis_result = convert_audio_to_text(
            task_id,
            video_info,
            transcription,
            duration,
            custom_word_packs=custom_word_packs,
        )
        
        logger.info(f"[任务 {task_id}] [音频处理模块] 音频流处理完成")
        return audio_result, text_analysis_result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 处理失败: {e}", exc_info=True)
        raise
    finally:
        # 分析完成后清理临时音频文件（避免磁盘堆积）
        try:
            if audio_path and os.path.exists(audio_path):
                os.remove(audio_path)
                logger.info(f"[任务 {task_id}] [音频处理模块] 已清理临时音频文件: {audio_path}")
        except Exception as cleanup_err:
            logger.warning(f"[任务 {task_id}] [音频处理模块] 清理临时音频文件失败: {cleanup_err}")


def extract_audio_features(task_id: str, video_info: Dict[str, Any], audio_path: str = None) -> Dict[str, Any]:
    """
    提取音频特征（Step 1）- 使用Librosa进行真实分析
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        audio_path: 音频文件路径（可选）
        
    Returns:
        音频特征分析结果
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 开始提取音频特征...")
    start_time = time.time()
    
    try:
        if audio_path and os.path.exists(audio_path):
            # 使用Librosa进行真实音频分析
            y, sr = librosa.load(audio_path, sr=16000, duration=60)  # 只加载前60秒，加快处理
            
            # 1. 音频质量（基于RMS能量）
            rms = librosa.feature.rms(y=y)
            audio_quality = float(np.mean(rms)) * 2  # 归一化到0-1
            audio_quality = min(1.0, max(0.0, audio_quality))
            
            # 2. 语音比例估计（基于零交叉率和能量）
            zcr = librosa.feature.zero_crossing_rate(y)
            mean_zcr = float(np.mean(zcr))
            speech_ratio = min(0.9, max(0.1, mean_zcr * 10))  # 粗略估计
            
            # 3. 音量水平
            volume_level = float(np.mean(np.abs(y)))
            volume_level = min(1.0, max(0.0, volume_level * 3))
            
            # 4. 噪声水平（基于能量方差）
            noise_level = float(np.std(rms))
            noise_level = min(0.5, max(0.0, noise_level))
            
            emotion_in_voice = infer_voice_emotion_from_features(audio_quality, volume_level, noise_level)

            result = {
                "hasAudio": True,
                "audioQuality": round(audio_quality, 4),
                "speechRatio": round(speech_ratio, 4),
                "musicRatio": round(max(0.0, min(1.0, 1 - speech_ratio - noise_level)), 4),
                "noiseLevel": round(noise_level, 4),
                "volumeLevel": round(volume_level, 4),
                "emotionInVoice": emotion_in_voice,
                "processingTime": round(time.time() - start_time, 2)
            }
            
            logger.info(f"[任务 {task_id}] [音频处理模块] 使用Librosa真实分析完成")
        else:
            # 无法读取音频，不再返回随机mock，返回明确空特征
            logger.warning(f"[任务 {task_id}] [音频处理模块] 音频文件不存在，使用估计值")
            result = {
                "hasAudio": False,
                "audioQuality": 0.0,
                "speechRatio": 0.0,
                "musicRatio": 0.0,
                "noiseLevel": 0.0,
                "volumeLevel": 0.0,
                "emotionInVoice": "neutral",
                "processingTime": round(time.time() - start_time, 2)
            }
        
        logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 音频特征提取完成，处理时间: {result['processingTime']:.2f}秒")
        return result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频特征提取失败: {e}")
        # 返回失败态空特征，避免伪造
        return {
            "hasAudio": False,
            "audioQuality": 0.0,
            "speechRatio": 0.0,
            "musicRatio": 0.0,
            "noiseLevel": 0.0,
            "volumeLevel": 0.0,
            "emotionInVoice": "neutral",
            "processingTime": round(time.time() - start_time, 2)
        }


def analyze_text_with_nlp(transcription: str, task_id: str, custom_word_packs: Optional[list] = None) -> Dict[str, Any]:
    """
    使用Jieba和SnowNLP进行真实文本分析（增强版：词云数据+敏感词检测）
    
    Args:
        transcription: ASR识别的文本
        task_id: 任务ID
        
    Returns:
        文本分析结果
    """
    try:
        if not transcription or transcription == "（未检测到语音内容）" or transcription == "（ASR识别失败）":
            # 无有效文本，返回默认值
            return {
                "keywords": [],
                "wordCloud": [],
                "sensitiveWords": [],
                "sentimentScore": 0.0,
                "sentimentLabel": "NEUTRAL",
            "topicCategory": "未知"
            }
        
        # 1. 关键词提取（使用TF-IDF，带权重用于词云）
        keywords_with_weight = jieba.analyse.extract_tags(transcription, topK=30, withWeight=True)
        
        # 词云数据（格式：[{name: '关键词', value: 权重}]）
        word_cloud = [{"name": word, "value": int(weight * 1000)} for word, weight in keywords_with_weight[:20]]
        
        # 顶级关键词（用于展示）
        keywords = [word for word, weight in keywords_with_weight[:7]]
        
        # 2. 敏感词检测（高校相关敏感词库）
        sensitive_words = detect_sensitive_words(transcription, custom_word_packs=custom_word_packs)
        
        # 3. 情感分析（SnowNLP）
        s = SnowNLP(transcription)
        sentiment_score_raw = s.sentiments  # 0-1之间
        # 转换为-1到1区间
        sentiment_score = (sentiment_score_raw * 2) - 1  # 0→-1, 0.5→0, 1→1
        
        # 4. 情感标签
        if sentiment_score > 0.3:
            sentiment_label = "POSITIVE"
        elif sentiment_score < -0.3:
            sentiment_label = "NEGATIVE"
        else:
            sentiment_label = "NEUTRAL"
        
        # 5. 主题分类（基于关键词规则，聚焦高校场景）
        topic_category = classify_topic_by_keywords(keywords)
        
        logger.info(f"[任务 {task_id}] [文本分析] 关键词: {keywords}, 敏感词: {sensitive_words}, 情感: {sentiment_label}({sentiment_score:.3f}), 主题: {topic_category}")
        
        return {
            "keywords": keywords,
            "wordCloud": word_cloud,  # 新增：词云数据
            "sensitiveWords": sensitive_words,  # 新增：敏感词列表
            "sentimentScore": round(sentiment_score, 4),
            "sentimentLabel": sentiment_label,
            "topicCategory": topic_category
        }
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [文本分析] NLP分析失败: {e}")
        # 返回失败态空结果，避免伪造关键词
        return {
            "keywords": [],
            "wordCloud": [],
            "sensitiveWords": [],
            "sentimentScore": 0.0,
            "sentimentLabel": "NEUTRAL",
            "topicCategory": "未知"
        }


def detect_sensitive_words(text: str, custom_word_packs: Optional[list] = None) -> list:
    """
    检测敏感词汇（高校风险预警相关）
    
    Args:
        text: 待检测文本
        
    Returns:
        检测到的敏感词列表
    """
    # 高校风险预警敏感词库
    sensitive_categories = {
        "政治敏感": ["政治", "政府", "领导", "官员", "腐败", "贪污"],
        "社会敏感": ["罢课", "罢工", "游行", "示威", "抗议"],
        "违法违规": ["作弊", "代考", "买卖", "诈骗", "传销"],
        "不良信息": ["自杀", "抑郁", "厌世", "报复", "伤害"],
        "商业广告": ["微信", "QQ", "联系方式", "代理", "兼职赚钱"],
        "学术不端": ["论文代写", "抄袭", "学术造假"]
    }
    
    detected = []
    seen = set()
    for category, words in sensitive_categories.items():
        for word in words:
            if word in text:
                key = (word, category)
                if key not in seen:
                    seen.add(key)
                    detected.append({"word": word, "category": category, "source": "builtin"})

    # 合并前端挂载词库（风险词库管理）
    custom_words = _extract_pack_words(custom_word_packs)
    for cw in custom_words:
        word = cw.get("text", "")
        category = cw.get("category", "自定义词库")
        if word and word in text:
            key = (word, category)
            if key not in seen:
                seen.add(key)
                detected.append({
                    "word": word,
                    "category": category,
                    "riskLevel": cw.get("riskLevel", "medium"),
                    "packName": cw.get("packName", "自定义词库"),
                    "source": "wordPack",
                })
    
    return detected


def classify_topic_by_keywords(keywords: list) -> str:
    """
    基于关键词规则分类主题（不返回"其他"）
    
    Args:
        keywords: 关键词列表
        
    Returns:
        主题分类
    """
    keywords_str = " ".join(keywords)
    
    # 主题关键词映射
    if any(word in keywords_str for word in ["选课", "系统", "教务", "制度", "政策", "管理"]):
        return "校园政策"
    elif any(word in keywords_str for word in ["学习", "考试", "课程", "教学", "作业", "论文"]):
        return "学术讨论"
    elif any(word in keywords_str for word in ["社团", "活动", "组织", "成员"]):
        return "社团活动"
    elif any(word in keywords_str for word in ["运动", "比赛", "体育", "健身", "球"]):
        return "体育运动"
    elif any(word in keywords_str for word in ["创业", "创新", "项目", "团队"]):
        return "科技创新"
    elif any(word in keywords_str for word in ["就业", "实习", "工作", "招聘", "面试"]):
        return "就业指导"
    elif any(word in keywords_str for word in ["科技", "技术", "编程", "开发", "AI", "算法"]):
        return "科技创新"
    elif any(word in keywords_str for word in ["艺术", "音乐", "表演", "舞蹈", "绘画"]):
        return "艺术表演"
    elif any(word in keywords_str for word in ["心理", "情绪", "压力", "焦虑"]):
        return "心理健康"
    elif any(word in keywords_str for word in ["志愿", "实践", "服务", "公益"]):
        return "社会实践"
    elif any(word in keywords_str for word in ["宿舍", "食堂", "寝室", "校园卡"]):
        return "校园生活"
    else:
        return "未知"


def generate_speech_events(transcription: str, duration: float, sentiment_score: float, keywords: list, task_id: str) -> list:
    """
    生成语音事件流（SpeechEvent类型）- 合理分段，不要把整段文本放一个事件
    
    Args:
        transcription: ASR识别文本
        duration: 视频时长
        sentiment_score: 情感分数
        keywords: 关键词列表
        task_id: 任务ID
        
    Returns:
        语音事件列表
    """
    speech_events = []
    event_id = 1
    
    # 如果无有效文本，返回空列表
    if not transcription or transcription in ["（未检测到语音内容）", "（ASR识别失败）"]:
        return []
    
    # 智能分段：按句号、问号、感叹号分割
    import re
    sentences = [s.strip() for s in re.split('[。！？!?]', transcription) if s.strip() and len(s.strip()) > 1]
    
    # 如果分段失败或文本过长，按长度强制分段
    if not sentences or len(transcription) > 200:
        max_length = 50  # 每段最多50字
        sentences = []
        for i in range(0, len(transcription), max_length):
            segment = transcription[i:i+max_length].strip()
            if segment:
                sentences.append(segment)
    
    # 限制事件数量（避免过多）
    if len(sentences) > 15:
        # 合并相邻的短句
        merged_sentences = []
        temp = ""
        for s in sentences:
            temp += s
            if len(temp) >= 30:  # 累积到30字以上再输出
                merged_sentences.append(temp)
                temp = ""
        if temp:
            merged_sentences.append(temp)
        sentences = merged_sentences[:15]  # 最多15个事件
    
    # 为每个句子生成一个语音事件
    segment_duration = duration / max(len(sentences), 1)
    
    for i, sentence in enumerate(sentences):
        start_time = int(i * segment_duration)
        end_time = min(int((i + 1) * segment_duration), int(duration))
        
        # 从句子中提取关键词
        sentence_keywords = [kw for kw in keywords if kw in sentence]
        
        neg_hit = any(word in sentence for word in ["抵制", "反对", "抗议", "不满", "垃圾", "傻逼"])
        base = 50 + int(-sentiment_score * 25)
        if neg_hit:
            base += 25
        base += min(10, len(sentence_keywords) * 2)
        risk_score = max(5, min(98, base))

        if risk_score >= 80:
            emotion_label = "愤怒"
            emotion_intensity = 0.9
            emotion_bg = "rgba(245, 108, 108, 0.15)"
            emotion_text = "#f56c6c"
        elif risk_score >= 60:
            emotion_label = "紧张"
            emotion_intensity = 0.7
            emotion_bg = "rgba(250, 173, 20, 0.15)"
            emotion_text = "#faad14"
        else:
            emotion_label = "平静"
            emotion_intensity = 0.35
            emotion_bg = "rgba(82, 196, 26, 0.15)"
            emotion_text = "#52c41a"
        
        speech_events.append({
            "id": f"speech-{event_id:03d}",
            "modality": "speech",
            "startTime": start_time,
            "endTime": end_time,
            "riskScore": risk_score,
            "transcript": sentence[:80],  # 限制单句最多80字符
            "keywords": sentence_keywords[:5],  # 限制关键词数量
            "emotion": {
                "label": emotion_label,
                "intensity": emotion_intensity,
                "bgColor": emotion_bg,
                "textColor": emotion_text
            },
            "confidence": max(70, min(98, 80 + len(sentence_keywords) * 3))
        })
        event_id += 1
    
    logger.info(f"[任务 {task_id}] [音频处理模块] 生成 {len(speech_events)} 个语音事件（原文{len(transcription)}字）")
    return speech_events


def generate_text_risks(duration: float, sentiment_score: float, sensitive_words: list) -> list:
    """
    生成文本风险点时间序列（基于5秒粒度）
    
    Args:
        duration: 视频时长
        sentiment_score: 情感分数
        sensitive_words: 敏感词列表
        
    Returns:
        文本风险点列表
    """
    time_granularity = 5
    num_segments = int(duration / time_granularity) + 1
    text_risks = []
    
    has_sensitive = len(sensitive_words) > 0
    
    sens_factor = min(0.35, len(sensitive_words) * 0.08)
    sent_factor = max(0.0, -float(sentiment_score)) * 0.35

    for i in range(num_segments):
        pos = i / max(1, num_segments - 1)
        center_boost = max(0.0, 1 - abs(pos - 0.5) * 2) * 0.15
        intensity = 0.18 + sens_factor + sent_factor + center_boost
        intensity = max(0.05, min(1.0, intensity))

        if len(sensitive_words) > 0 and intensity >= 0.7:
            reason = "检测到敏感关键词并伴随负向表达"
        elif intensity >= 0.55:
            reason = "文本情绪偏负面，存在传播风险"
        else:
            reason = "文本内容整体平稳"
        
        text_risks.append({
            "reason": reason,
            "intensity": round(intensity, 2)
        })
    
    return text_risks


def calculate_text_risk_scores(sentiment_score: float, keywords: list, sensitive_words: list, topic_category: str) -> dict:
    """
    计算文本维度的6个风险分数
    
    Args:
        sentiment_score: 情感分数
        keywords: 关键词列表
        sensitive_words: 敏感词列表
        topic_category: 主题分类
        
    Returns:
        6个维度的文本分数（0-100）
    """
    has_sensitive = len(sensitive_words) > 0
    keyword_count = len(keywords)
    neg = max(0.0, -float(sentiment_score))

    identity_score = int(35 + min(30, keyword_count * 4))

    university_keywords = ["大学", "学校", "校园", "学生", "老师", "教务", "宿舍", "食堂"]
    university_related_count = sum(1 for kw in keywords if any(uk in kw for uk in university_keywords))
    university_score = int(35 + min(50, university_related_count * 12) + (10 if "校园" in topic_category else 0))

    attitude_score = int(25 + neg * 60 + (8 if has_sensitive else 0))
    topic_score = int(35 + min(35, keyword_count * 4) + (8 if topic_category else 0))
    opinion_risk_score = int(20 + neg * 50 + min(30, len(sensitive_words) * 10))
    action_score = int(opinion_risk_score * 0.65 + attitude_score * 0.35)
    
    return {
        "identity": min(100, max(0, identity_score)),
        "university": min(100, max(0, university_score)),
        "topic": min(100, max(0, topic_score)),
        "attitude": min(100, max(0, attitude_score)),
        "opinionRisk": min(100, max(0, opinion_risk_score)),
        "action": min(100, max(0, action_score))
    }


def generate_preliminary_evidences(keywords: list, sentiment_score: float, topic_category: str, duration: float) -> dict:
    """
    生成6个维度的初步证据（包含text、visual、audio三种类型）
    
    Args:
        keywords: 关键词列表
        sentiment_score: 情感分数
        topic_category: 主题分类
        duration: 视频时长
        
    Returns:
        6个维度的初步证据字典（每个维度包含多种模态的证据）
    """
    evidences = {
        "identity": [],
        "university": [],
        "topic": [],
        "attitude": [],
        "opinionRisk": [],
        "action": []
    }

    d = max(1, int(duration))
    ts1 = min(d - 1, max(1, int(d * 0.2)))
    ts2 = min(d - 1, max(1, int(d * 0.5)))
    ts3 = min(d - 1, max(1, int(d * 0.8)))

    id_kw = next((k for k in keywords if any(w in k for w in ["学生", "同学", "班", "学号", "老师"])), None)
    uni_kw = next((k for k in keywords if any(w in k for w in ["大学", "校园", "学校", "教务", "宿舍", "食堂"])), None)
    topic_kw = keywords[0] if keywords else None

    if id_kw:
        evidences["identity"].append({"timestamp": ts1, "type": "text", "description": f"文本中出现身份相关线索：{id_kw}", "confidence": 85, "keyword": id_kw})
    if uni_kw:
        evidences["university"].append({"timestamp": ts1, "type": "text", "description": f"文本中出现高校相关线索：{uni_kw}", "confidence": 88, "keyword": uni_kw})
    if topic_kw:
        evidences["topic"].append({"timestamp": ts2, "type": "text", "description": f"主题归类依据关键词：{topic_kw}", "confidence": 82, "keyword": topic_kw})

    senti_100 = int((1 - sentiment_score) * 50)
    senti_100 = max(0, min(100, senti_100))
    att_desc = "检测到负向情绪表达" if sentiment_score < -0.2 else "情绪整体平稳"
    evidences["attitude"].append({"timestamp": ts2, "type": "text", "description": att_desc, "confidence": 80, "keyword": "sentiment", "sentimentScore": senti_100})

    op_desc = "负向表达可能带来传播风险" if sentiment_score < -0.3 else "文本传播风险较低"
    evidences["opinionRisk"].append({"timestamp": ts3, "type": "text", "description": op_desc, "confidence": 78, "keyword": "opinion"})
    if sentiment_score < -0.25:
        evidences["action"].append({"timestamp": ts3, "type": "text", "description": "建议进行人工复核", "confidence": 80, "keyword": "review"})

    return evidences


def convert_audio_to_text(
    task_id: str,
    video_info: Dict[str, Any],
    transcription: str,
    duration: float,
    custom_word_packs: Optional[list] = None,
) -> Dict[str, Any]:
    """
    文本分析（使用Jieba和SnowNLP进行真实NLP分析）
    适配新的前端数据结构
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        transcription: ASR识别的文本内容
        duration: 视频时长
        
    Returns:
        文本分析结果（包含语音事件、文本风险、特征分数、初步证据）
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 2: 开始文本分析...")
    start_time = time.time()
    
    # 使用真实的NLP分析
    nlp_result = analyze_text_with_nlp(transcription, task_id, custom_word_packs=custom_word_packs)
    
    sentiment_score = nlp_result["sentimentScore"]
    keywords = nlp_result["keywords"]
    sensitive_words = nlp_result.get("sensitiveWords", [])
    topic_category = nlp_result["topicCategory"]
    
    # ========== 构建文本模块结果 ==========
    
    # 1. 生成语音事件流
    speech_events = generate_speech_events(
        transcription,
        duration,
        sentiment_score,
        keywords,
        task_id
    )
    
    # 2. 生成文本风险时间序列
    text_risks = generate_text_risks(duration, sentiment_score, sensitive_words)
    
    # 3. 计算文本维度的特征分数
    text_risk_scores = calculate_text_risk_scores(
        sentiment_score,
        keywords,
        sensitive_words,
        topic_category
    )
    
    # 4. 生成初步证据（6个维度）
    preliminary_evidences = generate_preliminary_evidences(
        keywords,
        sentiment_score,
        topic_category,
        duration
    )
    
    # 5. 判断高校关联性（仅基于真实提取关键词）
    university_keywords = ["大学", "学院", "学校", "校园", "北大", "清华", "复旦", "教务", "选课", "宿舍", "食堂"]
    
    detected_keywords_list = [
        {
            "word": kw,
            "isUniversityRelated": any(uk in kw for uk in university_keywords)
        }
        for kw in keywords[:10]  # 最多取10个关键词
    ]
    
    # 6. 生成AI摘要（智能提取核心内容）
    def generate_smart_summary(text: str, max_length: int = 80) -> str:
        """生成智能摘要"""
        if not text or len(text) <= max_length:
            return text
        
        # 按句子分割
        import re
        sentences = [s.strip() for s in re.split('[。！？!?]', text) if s.strip()]
        
        if not sentences:
            return text[:max_length] + "..."
        
        # 优先选择包含关键词的句子
        scored_sentences = []
        for sent in sentences:
            score = 0
            # 关键词加分
            for kw in keywords[:5]:  # 只检查前5个关键词
                if kw in sent:
                    score += 2
            # 学校相关词汇加分
            if any(word in sent for word in ["学校", "大学", "校园", "老师", "同学"]):
                score += 1
            # 情感词汇加分
            if any(word in sent for word in ["不满", "批评", "抗议", "反对", "支持", "赞同"]):
                score += 1
            scored_sentences.append((score, sent))
        
        # 按分数排序，取最高分的句子
        scored_sentences.sort(reverse=True, key=lambda x: x[0])
        
        summary = ""
        for score, sent in scored_sentences:
            if len(summary) + len(sent) <= max_length:
                summary += sent + "。"
            else:
                break
        
        if not summary:
            summary = sentences[0][:max_length] + "..."
        
        return summary
    
    ai_description = generate_smart_summary(transcription, 80)
    
    result = {
        # 视频基本信息补充
        "videoInfo": {
            "description": ai_description,
            "detectedKeywords": detected_keywords_list
        },
        
        # 语音事件流
        "speechEvents": speech_events,
        
        # 时间轴-文本风险
        "textRisks": text_risks,
        
        # 特征数据（用于后续融合）
        "features": {
            "transcription": transcription,
            "keywords": keywords,
            "wordCloud": nlp_result.get("wordCloud", []),
            "sensitiveWords": sensitive_words,
            "sentimentScore": sentiment_score,
            "sentimentLabel": nlp_result["sentimentLabel"],
            "topicCategory": topic_category,
            "languageConfidence": 0.95 if transcription not in ["（未检测到语音内容）", "（ASR识别失败）"] else 0.0,
            
            # 6个维度的文本风险分数
            "textRiskScores": text_risk_scores
        },
        
        # 初步证据（6个维度）
        "preliminaryEvidences": preliminary_evidences,
        
        "processingTime": round(time.time() - start_time, 2)
    }
    
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 2: 文本分析完成")
    logger.info(f"[任务 {task_id}] - 语音事件: {len(speech_events)}个")
    logger.info(f"[任务 {task_id}] - 文本风险点: {len(text_risks)}个")
    logger.info(f"[任务 {task_id}] - 检测到关键词: {len(keywords)}个")
    logger.info(f"[任务 {task_id}] - 处理时间: {result['processingTime']:.2f}秒")
    
    return result

