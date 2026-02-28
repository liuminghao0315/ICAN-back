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
import jieba
import jieba.analyse
from snownlp import SnowNLP
import librosa
import numpy as np
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
    
    # 生成5-8个声学事件，时间分散
    event_count = min(8, max(5, int(duration / 10)))
    
    # 生成随机时间点并排序
    time_points = sorted([random.randint(3, int(duration) - 3) for _ in range(event_count)])
    
    for i, start_time in enumerate(time_points):
        audio_events.append({
            "id": f"audio-{event_id:03d}",
            "modality": "audio-effect",
            "startTime": start_time,
            "endTime": min(start_time + random.randint(2, 5), int(duration)),
            "riskScore": random.randint(50, 90),
            "description": random.choice([
                "检测到重物撞击声（疑似拍桌动作）",
                "检测到异常音量突变",
                "检测到背景噪音异常",
                "检测到愤怒咆哮声，音量骤升",
                "检测到尖锐声响",
                "检测到金属碰撞声"
            ]),
            "intensity": round(random.uniform(0.6, 0.95), 2),
            "confidence": random.randint(80, 95)
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
    
    # 生成情绪波动曲线
    for i in range(num_segments):
        if speech_ratio < 0.3:
            # 语音少，情绪强度低
            intensity = random.uniform(0.15, 0.35)
            reason = random.choice(["无语音或语音很少", "背景音乐", "安静片段"])
        elif i < 2:
            # 开头段
            intensity = random.uniform(0.25, 0.45)
            reason = random.choice(["语音平稳", "开始介绍", "平静陈述"])
        elif i >= num_segments - 2:
            # 结尾段
            intensity = random.uniform(0.3, 0.5)
            reason = random.choice(["情绪平复", "结束陈述", "趋于平静"])
        else:
            # 中间段：可能出现情绪高峰
            if random.random() > 0.7:
                intensity = random.uniform(0.8, 0.98)
                reason = random.choice([
                    "检测到愤怒咆哮，音量突然增大",
                    "情绪激动，语速加快",
                    "语调升高，情绪紧张"
                ])
            else:
                intensity = random.uniform(0.35, 0.7)
                reason = random.choice([
                    "语气正常",
                    "语速平稳",
                    "情绪中等",
                    "持续陈述"
                ])
        
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
    # 1. 身份置信度（语音比例高时分数高）
    identity_score = int(60 + speech_ratio * 35 + random.randint(-5, 10))
    
    # 2. 学校关联度（音频模态需要结合文本，暂给中等分）
    university_score = random.randint(70, 90)
    
    # 3. 负面情感度（基于音量和语速）
    attitude_score = int(40 + volume_level * 40 + random.randint(-10, 15))
    
    # 4. 主题分数
    topic_score = random.randint(60, 90)
    
    # 5. 舆论风险（音频情绪强度影响）
    opinion_risk_score = int(45 + volume_level * 30 + random.randint(-10, 20))
    
    # 6. 处置紧迫度
    action_score = random.randint(50, 85)
    
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
        
        # Step 3: ASR转文本
        transcription = asr_recognize(audio_path, task_id)
        
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
            
            "processingTime": audio_features.get("processingTime", 0)
        }
        
        # ========== 构建文本模块结果（Step 3: Text → 75%） ==========
        text_analysis_result = convert_audio_to_text(task_id, video_info, transcription, duration)
        
        logger.info(f"[任务 {task_id}] [音频处理模块] 音频流处理完成")
        return audio_result, text_analysis_result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 处理失败: {e}", exc_info=True)
        raise
    finally:
        # 保留音频文件用于验证
        pass


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
            
            result = {
                "hasAudio": True,
                "audioQuality": round(audio_quality, 4),
                "speechRatio": round(speech_ratio, 4),
                "musicRatio": round(random.uniform(0, 0.3), 4),  # 音乐检测较复杂，暂用估计值
                "noiseLevel": round(noise_level, 4),
                "volumeLevel": round(volume_level, 4),
                "emotionInVoice": "neutral",  # 音频情感检测较复杂，暂用默认值
                "processingTime": round(time.time() - start_time, 2)
            }
            
            logger.info(f"[任务 {task_id}] [音频处理模块] 使用Librosa真实分析完成")
        else:
            # 降级方案：使用mock数据
            logger.warning(f"[任务 {task_id}] [音频处理模块] 音频文件不存在，使用估计值")
            result = {
                "hasAudio": True,
                "audioQuality": round(random.uniform(0.6, 1.0), 4),
                "speechRatio": round(random.uniform(0.3, 0.8), 4),
                "musicRatio": round(random.uniform(0, 0.3), 4),
                "noiseLevel": round(random.uniform(0, 0.3), 4),
                "volumeLevel": round(random.uniform(0.4, 0.8), 4),
                "emotionInVoice": random.choice(["calm", "energetic", "neutral"]),
                "processingTime": round(time.time() - start_time, 2)
            }
        
        logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 音频特征提取完成，处理时间: {result['processingTime']:.2f}秒")
        return result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频特征提取失败: {e}")
        # 返回默认值
        return {
            "hasAudio": True,
            "audioQuality": 0.7,
            "speechRatio": 0.5,
            "musicRatio": 0.1,
            "noiseLevel": 0.2,
            "volumeLevel": 0.6,
            "emotionInVoice": "neutral",
            "processingTime": round(time.time() - start_time, 2)
        }


def analyze_text_with_nlp(transcription: str, task_id: str) -> Dict[str, Any]:
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
            "topicCategory": "校园生活"
            }
        
        # 1. 关键词提取（使用TF-IDF，带权重用于词云）
        keywords_with_weight = jieba.analyse.extract_tags(transcription, topK=30, withWeight=True)
        
        # 词云数据（格式：[{name: '关键词', value: 权重}]）
        word_cloud = [{"name": word, "value": int(weight * 1000)} for word, weight in keywords_with_weight[:20]]
        
        # 顶级关键词（用于展示）
        keywords = [word for word, weight in keywords_with_weight[:7]]
        
        # 2. 敏感词检测（高校相关敏感词库）
        sensitive_words = detect_sensitive_words(transcription)
        
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
        # 返回默认值
        return {
            "keywords": ["大学生", "校园"],
            "wordCloud": [],
            "sensitiveWords": [],
            "sentimentScore": 0.0,
            "sentimentLabel": "NEUTRAL",
            "topicCategory": "校园生活"
        }


def detect_sensitive_words(text: str) -> list:
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
    for category, words in sensitive_categories.items():
        for word in words:
            if word in text:
                detected.append({"word": word, "category": category})
    
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
        # 默认返回校园生活（不返回"其他"）
        return "校园生活"


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
        
        # 根据情感分数和关键词判断风险
        if any(word in sentence for word in ["抵制", "反对", "抗议", "不满", "垃圾", "傻逼"]):
            risk_score = random.randint(85, 98)
            emotion_label = "愤怒"
            emotion_intensity = 0.9
            emotion_bg = "rgba(245, 108, 108, 0.15)"
            emotion_text = "#f56c6c"
        elif sentiment_score < -0.3:
            risk_score = random.randint(65, 85)
            emotion_label = random.choice(["严肃", "紧张"])
            emotion_intensity = 0.7
            emotion_bg = "rgba(250, 173, 20, 0.15)"
            emotion_text = "#faad14"
        elif sentiment_score > 0.3:
            risk_score = random.randint(15, 35)
            emotion_label = "平静"
            emotion_intensity = 0.3
            emotion_bg = "rgba(82, 196, 26, 0.15)"
            emotion_text = "#52c41a"
        else:
            risk_score = random.randint(30, 60)
            emotion_label = "平静"
            emotion_intensity = 0.4
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
            "confidence": random.randint(85, 98)
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
    
    for i in range(num_segments):
        if i < 2:
            # 开头段：低风险
            intensity = random.uniform(0.12, 0.28)
            reason = random.choice(["开场无语音", "平静介绍", "正常陈述"])
        elif i >= num_segments - 2:
            # 结尾段
            intensity = random.uniform(0.25, 0.45)
            reason = random.choice(["总结陈述", "情绪平复", "结束语"])
        else:
            # 中间段：根据情感和敏感词生成
            if has_sensitive and random.random() > 0.6:
                intensity = random.uniform(0.85, 1.0)
                reason = random.choice([
                    "情绪激烈，使用极端词汇批评",
                    "持续批评，出现煽动性词汇",
                    "检测到敏感关键词"
                ])
            elif sentiment_score < -0.3:
                intensity = random.uniform(0.55, 0.75)
                reason = random.choice([
                    "表达不满，涉及负面评价",
                    "持续表达不满情绪",
                    "可能引发共鸣"
                ])
            else:
                intensity = random.uniform(0.25, 0.55)
                reason = random.choice([
                    "正常陈述",
                    "提及相关信息",
                    "客观描述"
                ])
        
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
    
    # 1. 身份置信度（基于关键词）
    identity_score = int(60 + keyword_count * 4 + random.randint(-5, 10))
    
    # 2. 学校关联度（基于关键词和主题）
    university_score = 0
    university_keywords = ["大学", "学校", "校园", "学生", "老师", "教务", "宿舍", "食堂"]
    university_related_count = sum(1 for kw in keywords if any(uk in kw for uk in university_keywords))
    university_score = int(50 + university_related_count * 10 + random.randint(-5, 15))
    
    # 3. 负面情感度（基于情感分数）
    if sentiment_score < -0.5:
        attitude_score = random.randint(80, 95)
    elif sentiment_score < -0.2:
        attitude_score = random.randint(60, 80)
    elif sentiment_score > 0.2:
        attitude_score = random.randint(15, 35)
    else:
        attitude_score = random.randint(40, 60)
    
    # 4. 主题分数（基于主题分类和关键词）
    topic_score = int(60 + keyword_count * 3 + random.randint(-5, 15))
    
    # 5. 舆论风险（基于敏感词和情感）
    if has_sensitive:
        opinion_risk_score = random.randint(70, 90)
    elif sentiment_score < -0.3:
        opinion_risk_score = random.randint(55, 75)
    else:
        opinion_risk_score = random.randint(35, 60)
    
    # 6. 处置紧迫度（基于综合判断）
    if has_sensitive and sentiment_score < -0.5:
        action_score = random.randint(75, 95)
    elif has_sensitive or sentiment_score < -0.3:
        action_score = random.randint(55, 80)
    else:
        action_score = random.randint(40, 65)
    
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
    
    # ========== 1. 身份相关证据（text + visual + audio）==========
    # 文本证据
    identity_keywords = ["学生", "我是", "我们", "同学", "班级", "学号"]
    for kw in keywords[:2]:
        if any(ik in kw for ik in identity_keywords):
            evidences["identity"].append({
                "timestamp": random.randint(5, max(10, int(duration) - 5)),
                "type": "text",
                "description": f"身份相关词汇：{kw}",
                "confidence": random.randint(80, 95),
                "keyword": kw
            })
    # 补充文本证据
    while len([e for e in evidences["identity"] if e["type"] == "text"]) < 2:
        evidences["identity"].append({
            "timestamp": random.randint(5, max(10, int(duration) - 10)),
            "type": "text",
            "description": random.choice(["使用第一人称表达", "疑似在校学生语气"]),
            "confidence": random.randint(75, 88),
            "keyword": random.choice(identity_keywords)
        })

    # 视觉证据 - 已移除，visual类型仅用于CV检测，不作为证据
    # for i in range(2):
    #     evidences["identity"].append({
    #         "timestamp": random.randint(10, max(15, int(duration) - 10)),
    #         "type": "visual",
    #         "description": random.choice([
    #             "检测到学生证或校园卡",
    #             "识别到校服着装",
    #             "检测到学生常去场景（教室/图书馆）"
    #         ]),
    #         "confidence": random.randint(80, 92),
    #         "keyword": "visual-identity"
    #     })

    # 音频证据
    evidences["identity"].append({
        "timestamp": random.randint(8, max(12, int(duration) - 8)),
        "type": "audio",
        "description": "声音特征符合年轻学生群体",
        "confidence": random.randint(75, 88),
        "keyword": "voice-age"
    })
    
    # ========== 2. 高校相关证据（text + visual + audio）==========
    # 文本证据
    university_keywords = ["大学", "北大", "清华", "学校", "校园", "教务", "选课", "宿舍", "食堂"]
    for kw in keywords[:2]:
        if any(uk in kw for uk in university_keywords):
            evidences["university"].append({
                "timestamp": random.randint(5, max(10, int(duration) - 5)),
                "type": "text",
                "description": f"高校关键词：{kw}",
                "confidence": random.randint(85, 98),
                "keyword": kw
            })
    while len([e for e in evidences["university"] if e["type"] == "text"]) < 2:
        evidences["university"].append({
            "timestamp": random.randint(10, max(15, int(duration) - 10)),
            "type": "text",
            "description": "多次提及学校相关内容",
            "confidence": random.randint(80, 92),
            "keyword": random.choice(university_keywords)
        })

    # 视觉证据 - 已移除，visual类型仅用于CV检测，不作为证据
    # for i in range(2):
    #     evidences["university"].append({
    #         "timestamp": random.randint(5, max(10, int(duration) - 5)),
    #         "type": "visual",
    #         "description": random.choice([
    #             "识别到学校logo或校徽",
    #             "检测到校园标志性建筑",
    #             "识别到教学楼内部场景"
    #         ]),
    #         "confidence": random.randint(88, 98),
    #         "keyword": "campus-visual"
    #     })

    # 音频证据
    evidences["university"].append({
        "timestamp": random.randint(15, max(20, int(duration) - 10)),
        "type": "audio",
        "description": "背景声包含校园特征音（如上下课铃声）",
        "confidence": random.randint(70, 85),
        "keyword": "campus-audio"
    })
    
    # ========== 3. 主题相关证据（text + visual）==========
    for i in range(2):
        evidences["topic"].append({
            "timestamp": random.randint(10, max(15, int(duration) - 10)),
            "type": "text",
            "description": f"主题关键内容{i+1}",
            "confidence": random.randint(80, 95),
            "keyword": topic_category or "校园话题"
        })
    # 视觉证据 - 已移除，visual类型仅用于CV检测，不作为证据
    # evidences["topic"].append({
    #     "timestamp": random.randint(12, max(18, int(duration) - 12)),
    #     "type": "visual",
    #     "description": "画面内容与主题相关",
    #     "confidence": random.randint(75, 90),
    #     "keyword": "topic-visual"
    # })

    # ========== 4. 态度相关证据（text + audio，包含sentimentScore）- 随机正负面 ==========
    # 随机生成正面、负面、中性情绪，不要总是负面
    emotion_types = []
    for _ in range(4):  # 生成4个证据
        rand_val = random.random()
        if rand_val < 0.25:  # 25%概率负面
            emotion_types.append({
                "sentiment": "negative",
                "score": random.randint(70, 95),
                "words": ["失望", "不满", "批评", "抱怨", "质疑", "愤怒"],
                "audio_desc": ["语气情绪激动，音量突然升高", "检测到愤怒情绪", "语速加快，情绪紧张"]
            })
        elif rand_val < 0.65:  # 40%概率中性
            emotion_types.append({
                "sentiment": "neutral",
                "score": random.randint(40, 60),
                "words": ["讨论", "陈述", "说明", "提及", "谈到"],
                "audio_desc": ["语调平稳，正常陈述", "语气平和", "保持冷静态度"]
            })
        else:  # 35%概率正面
            emotion_types.append({
                "sentiment": "positive",
                "score": random.randint(10, 35),
                "words": ["赞同", "支持", "满意", "认可", "欣赏"],
                "audio_desc": ["语气愉悦，表达认可", "语调轻松", "情绪积极"]
            })
    
    # 文本证据（2个）
    for i in range(2):
        emotion = emotion_types[i]
        evidences["attitude"].append({
            "timestamp": random.randint(15, max(20, int(duration) - 10)),
            "type": "text",
            "description": f"{random.choice(emotion['words'])}情绪表达",
            "confidence": random.randint(85, 95),
            "keyword": random.choice(emotion['words']),
            "sentimentScore": emotion["score"]
        })
    
    # 音频证据（2个）
    for i in range(2):
        emotion = emotion_types[i + 2]
        evidences["attitude"].append({
            "timestamp": random.randint(18, max(25, int(duration) - 12)),
            "type": "audio",
            "description": random.choice(emotion["audio_desc"]),
            "confidence": random.randint(80, 92),
            "keyword": "emotion-audio",
            "sentimentScore": emotion["score"]
        })
    
    # ========== 5. 舆论风险证据（text + visual）- 增加变化性 ==========
    risk_templates = [
        {
            "text_descs": ["可能引发共鸣的措辞", "使用煽动性词汇", "呼吁集体行动", "表达强烈诉求"],
            "text_keywords": ["大家", "所有人", "我们应该", "一起", "共同"],
            "visual_desc": ["画面显示群体聚集", "检测到横幅标语", "多人同框出现"]
        },
        {
            "text_descs": ["正常表达观点", "客观陈述", "提出建议", "理性讨论"],
            "text_keywords": ["建议", "希望", "可以", "应该", "认为"],
            "visual_desc": ["正常画面内容", "单人场景", "静态画面"]
        },
        {
            "text_descs": ["情感化表达", "个人感受分享", "经历叙述"],
            "text_keywords": ["我觉得", "我认为", "我的体验", "感觉"],
            "visual_desc": ["个人镜头特写", "情绪化表情", "手势动作"]
        }
    ]
    
    # 随机选择风险等级
    risk_level = random.choice(risk_templates)
    
    for i in range(2):
        evidences["opinionRisk"].append({
            "timestamp": random.randint(20, max(25, int(duration) - 10)),
            "type": "text",
            "description": random.choice(risk_level["text_descs"]),
            "confidence": random.randint(75, 90),
            "keyword": random.choice(risk_level["text_keywords"])
        })
    # 视觉证据 - 已移除，visual类型仅用于CV检测，不作为证据
    # evidences["opinionRisk"].append({
    #     "timestamp": random.randint(22, max(28, int(duration) - 8)),
    #     "type": "visual",
    #     "description": random.choice(risk_level["visual_desc"]),
    #     "confidence": random.randint(70, 88),
    #     "keyword": "visual-context"
    # })

    # ========== 6. 处置建议证据（text + audio + visual）- 增加变化性 ==========
    action_scenarios = [
        {  # 高风险场景
            "text_desc": ["关键负面词汇出现", "极端表达", "煽动性言论"],
            "text_keywords": ["批评", "抗议", "抵制", "反对"],
            "audio_desc": ["情绪激烈片段，持续高音量", "愤怒咆哮", "激动发言"],
            "visual_desc": ["检测到激动手势", "肢体语言强烈", "表情激动"]
        },
        {  # 中等风险场景
            "text_desc": ["负面评价", "不满表达", "质疑内容"],
            "text_keywords": ["不满", "质疑", "失望", "不好"],
            "audio_desc": ["语气较重", "情绪波动", "语速加快"],
            "visual_desc": ["表情变化", "手势辅助", "眉头紧锁"]
        },
        {  # 低风险场景
            "text_desc": ["正常表达", "客观描述", "平和陈述"],
            "text_keywords": ["说明", "介绍", "讨论", "分享"],
            "audio_desc": ["语气平稳", "正常语调", "声音清晰"],
            "visual_desc": ["表情自然", "姿态放松", "正常画面"]
        }
    ]
    
    # 随机选择场景（倾向于随机分布）
    scenario = random.choice(action_scenarios)
    
    evidences["action"].append({
        "timestamp": random.randint(15, max(20, int(duration) - 5)),
        "type": "text",
        "description": random.choice(scenario["text_desc"]),
        "confidence": random.randint(80, 92),
        "keyword": random.choice(scenario["text_keywords"])
    })
    evidences["action"].append({
        "timestamp": random.randint(18, max(24, int(duration) - 6)),
        "type": "audio",
        "description": random.choice(scenario["audio_desc"]),
        "confidence": random.randint(78, 90),
        "keyword": "audio-action"
    })
    # 视觉证据 - 已移除，visual类型仅用于CV检测，不作为证据
    # evidences["action"].append({
    #     "timestamp": random.randint(20, max(26, int(duration) - 8)),
    #     "type": "visual",
    #     "description": random.choice(scenario["visual_desc"]),
    #     "confidence": random.randint(75, 88),
    #     "keyword": "gesture-visual"
    # })

    return evidences


def convert_audio_to_text(task_id: str, video_info: Dict[str, Any], transcription: str, duration: float) -> Dict[str, Any]:
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
    nlp_result = analyze_text_with_nlp(transcription, task_id)
    
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
    
    # 5. 判断高校关联性（Mock阶段强制添加一些高校关键词）
    university_keywords = ["大学", "学院", "学校", "校园", "北大", "清华", "复旦", "教务", "选课", "宿舍", "食堂"]
    
    # 强制添加一些高校关键词用于展示（如果原关键词列表中没有）
    has_university_kw = any(any(uk in kw for uk in university_keywords) for kw in keywords)
    if not has_university_kw or len(keywords) < 3:
        # 添加高校关键词
        additional_keywords = ["北京大学", "选课系统", "教务处", "校园卡"]
        keywords.extend([kw for kw in additional_keywords if kw not in keywords])
    
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
            "languageConfidence": 0.95,
            
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

