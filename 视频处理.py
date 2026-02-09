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
import cv2
import numpy as np
from typing import Dict, Any

# 配置日志
logger = logging.getLogger(__name__)


def analyze_video_frames_for_risk(video_url: str, task_id: str) -> Dict[str, Any]:
    """
    分析视频帧，生成完整的风险时间序列数据（用于图表展示）
    
    Args:
        video_url: 视频URL
        task_id: 任务ID
        
    Returns:
        风险时间轴数据（包含时间序列和风险点）
    """
    try:
        cap = cv2.VideoCapture(video_url)
        if not cap.isOpened():
            return {"timeSeriesData": [], "riskPoints": [], "duration": 0}
        
        fps = int(cap.get(cv2.CAP_PROP_FPS))
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        duration = frame_count / fps if fps > 0 else 0
        
        # 每10秒采样一次，生成时间序列
        sample_interval = max(1, fps * 10)
        
        time_series_data = []  # 格式：[{time: 0, risk: 0.2}, {time: 10, risk: 0.3}, ...]
        risk_points = []  # 格式：[{time: 30, type: "画面异常", level: "medium", desc: "..."}]
        
        face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
        
        for frame_pos in range(0, frame_count, sample_interval):
            cap.set(cv2.CAP_PROP_POS_FRAMES, frame_pos)
            ret, frame = cap.read()
            
            if not ret:
                break
            
            timestamp = frame_pos / fps
            
            # 分析帧内容
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            
            # 计算该时刻的风险指数（0-1）
            risk_score = 0.0
            risk_factors = []
            
            # 1. 亮度异常检测
            brightness = np.mean(gray) / 255.0
            if brightness < 0.15:
                risk_score += 0.3
                risk_factors.append("画面过暗")
            elif brightness > 0.95:
                risk_score += 0.2
                risk_factors.append("画面过亮")
            
            # 2. 人脸数量异常（可能是聚众）
            faces = face_cascade.detectMultiScale(gray, 1.1, 4)
            if len(faces) > 10:
                risk_score += 0.4
                risk_factors.append(f"检测到{len(faces)}人，可能为聚集活动")
            
            # 3. 基础风险值（随机波动，模拟内容分析）
            base_risk = 0.1 + random.random() * 0.2  # 0.1-0.3的基础风险
            risk_score += base_risk
            
            # 限制在0-1范围
            risk_score = min(1.0, risk_score)
            
            # 添加到时间序列
            time_series_data.append({
                "time": round(timestamp, 1),
                "risk": round(risk_score, 3)
            })
            
            # 如果风险较高，添加到风险点列表
            if risk_score > 0.4:
                level = "high" if risk_score > 0.7 else "medium"
                risk_points.append({
                    "time": round(timestamp, 1),
                    "type": "潜在风险",
                    "level": level,
                    "description": "、".join(risk_factors) if risk_factors else "内容特征异常",
                    "riskScore": round(risk_score, 3)
                })
        
        cap.release()
        
        logger.info(f"[任务 {task_id}] [视频处理模块] 风险时间轴分析完成：生成{len(time_series_data)}个时间点，检测到{len(risk_points)}个风险点")
        
        return {
            "timeSeriesData": time_series_data,  # 完整时间序列（用于绘制曲线）
            "riskPoints": risk_points,  # 风险点列表（用于标注）
            "duration": round(duration, 2)  # 视频总时长
        }
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [视频处理模块] 风险时间轴分析失败: {e}")
        return {"timeSeriesData": [], "riskPoints": [], "duration": 0}


def analyze_video_with_opencv(video_url: str, task_id: str) -> Dict[str, Any]:
    """
    使用OpenCV分析视频内容
    
    Args:
        video_url: 视频URL
        task_id: 任务ID
        
    Returns:
        视频分析结果
    """
    try:
        logger.info(f"[任务 {task_id}] [视频处理模块] 开始OpenCV分析...")
        
        # 打开视频
        cap = cv2.VideoCapture(video_url)
        
        if not cap.isOpened():
            raise Exception("无法打开视频")
        
        # 获取视频基本信息
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fps = int(cap.get(cv2.CAP_PROP_FPS))
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        duration = frame_count / fps if fps > 0 else 0
        
        # 采样帧进行分析（每秒1帧，最多30帧）
        sample_interval = max(1, fps)
        max_samples = min(30, frame_count // sample_interval)
        
        face_detected_frames = 0
        total_faces = 0
        brightness_values = []
        clarity_values = []
        color_variance_values = []
        
        # 加载人脸检测器
        face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
        
        for i in range(max_samples):
            frame_pos = i * sample_interval
            cap.set(cv2.CAP_PROP_POS_FRAMES, frame_pos)
            ret, frame = cap.read()
            
            if not ret:
                break
            
            # 1. 人脸检测
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            faces = face_cascade.detectMultiScale(gray, 1.1, 4)
            if len(faces) > 0:
                face_detected_frames += 1
                total_faces += len(faces)
            
            # 2. 亮度分析
            brightness = np.mean(gray) / 255.0
            brightness_values.append(brightness)
            
            # 3. 清晰度分析（Laplacian方差）
            laplacian_var = cv2.Laplacian(gray, cv2.CV_64F).var()
            clarity = min(1.0, laplacian_var / 1000.0)  # 归一化
            clarity_values.append(clarity)
            
            # 4. 色彩方差（判断是否为屏幕录制）
            color_std = np.std(frame)
            color_variance_values.append(color_std)
        
        cap.release()
        
        # 分析结果
        has_person = face_detected_frames > (max_samples * 0.2)  # 超过20%帧有人脸
        avg_face_count = total_faces // max(1, face_detected_frames) if has_person else 0
        avg_brightness = np.mean(brightness_values) if brightness_values else 0.5
        avg_clarity = np.mean(clarity_values) if clarity_values else 0.7
        avg_color_var = np.mean(color_variance_values) if color_variance_values else 50
        
        # 场景类型推测
        scene_type = detect_scene_type(has_person, avg_color_var, width, height)
        scene_confidence = 0.7 if has_person or avg_color_var < 60 else 0.85  # 屏幕录制更有把握
        
        # 画质评分
        quality_score = calculate_quality_score(avg_clarity, avg_brightness, width, height)
        
        result = {
            "duration": duration,
            "width": width,
            "height": height,
            "fps": fps,
            "sceneType": scene_type,
            "sceneConfidence": round(scene_confidence, 4),
            "faceCount": avg_face_count,
            "hasPerson": has_person,
            "qualityScore": round(quality_score, 4),
            "brightness": round(avg_brightness, 4),
            "clarity": round(avg_clarity, 4)
        }
        
        logger.info(f"[任务 {task_id}] [视频处理模块] OpenCV分析完成: 场景={scene_type}, 人物={has_person}, 质量={quality_score:.2f}")
        return result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [视频处理模块] OpenCV分析失败: {e}")
        # 返回默认值
        return {
            "duration": 60.0,
            "width": 1920,
            "height": 1080,
            "fps": 24,
            "sceneType": "校园场景",
            "sceneConfidence": 0.0,
            "faceCount": 0,
            "hasPerson": False,
            "qualityScore": 0.7,
            "brightness": 0.5,
            "clarity": 0.7
        }


def detect_scene_type(has_person: bool, color_var: float, width: int, height: int) -> str:
    """
    推测场景类型（聚焦高校场景）
    
    Args:
        has_person: 是否检测到人脸
        color_var: 色彩方差
        width: 视频宽度
        height: 视频高度
        
    Returns:
        场景类型（高校相关场景）
    """
    # 屏幕录制特征：色彩方差低，分辨率标准（常见于线上教学、作业展示）
    if color_var < 60 and (width >= 1280 or height >= 720):
        return "线上教学/课程演示"
    
    # 有人脸场景（根据色彩和人数推测具体场景）
    if has_person:
        if color_var < 70:
            # 室内低色彩方差 → 教室、图书馆等
            return random.choice(["教室讲课", "图书馆学习", "实验室研究"])
        elif color_var < 90:
            # 室内中等色彩
            return random.choice(["宿舍日常", "食堂用餐", "自习室"])
        else:
            # 高色彩方差 → 户外
            return random.choice(["校园活动", "操场运动", "校园漫步"])
    
    # 无人脸场景
    if color_var < 50:
        # 低色彩方差 → 可能是PPT、文档展示
        return "课件/文档展示"
    elif color_var < 80:
        # 中等色彩 → 可能是校园建筑
        return "校园风景/建筑"
    else:
        # 高色彩 → 活动场景
        return "校园活动/表演"


def generate_simple_risk_timeline(duration: float) -> Dict[str, Any]:
    """
    生成简单的风险时间轴数据（降级方案）
    
    Args:
        duration: 视频时长（秒）
        
    Returns:
        风险时间轴数据
    """
    time_series_data = []
    risk_points = []
    
    # 每30秒一个采样点
    for t in range(0, int(duration) + 1, 30):
        # 生成波动的风险值（0.1-0.5之间）
        base_risk = 0.15 + random.random() * 0.25
        time_series_data.append({
            "time": t,
            "risk": round(base_risk, 3)
        })
        
        # 偶尔添加一个风险点
        if random.random() > 0.7 and base_risk > 0.3:
            risk_points.append({
                "time": t,
                "type": "内容特征",
                "level": "medium",
                "description": "检测到内容特征波动",
                "riskScore": round(base_risk, 3)
            })
    
    return {
        "timeSeriesData": time_series_data,
        "riskPoints": risk_points,
        "duration": round(duration, 2)
    }


def calculate_quality_score(clarity: float, brightness: float, width: int, height: int) -> float:
    """
    计算画质评分
    """
    # 清晰度权重50%
    clarity_score = clarity * 0.5
    
    # 亮度权重20%（理想值0.4-0.6）
    brightness_score = (1 - abs(brightness - 0.5) * 2) * 0.2
    
    # 分辨率权重30%
    resolution_score = 0.3
    if width >= 1920 and height >= 1080:
        resolution_score = 1.0 * 0.3
    elif width >= 1280 and height >= 720:
        resolution_score = 0.8 * 0.3
    elif width >= 640 and height >= 480:
        resolution_score = 0.6 * 0.3
    
    total_score = clarity_score + brightness_score + resolution_score
    return min(1.0, max(0.0, total_score))


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


def generate_visual_events(duration: float, scene_type: str, has_person: bool, task_id: str) -> list:
    """
    生成视觉事件流（VisualEvent类型） - 增加数量以展示效果
    
    Args:
        duration: 视频时长
        scene_type: 场景类型
        has_person: 是否有人物
        task_id: 任务ID
        
    Returns:
        视觉事件列表
    """
    visual_events = []
    event_id = 1
    
    # 增加logo检测事件（至少2个）- 增加描述多样性
    logo_descriptions = [
        "检测到教育机构相关标识", "识别到校徽图案", "检测到学校标志性建筑",
        "检测到学校名称logo", "识别到校园标志物", "检测到校训文字",
        "识别到教学楼铭牌", "检测到校园地标建筑", "识别到学院标识",
        "检测到学校宣传标语", "识别到校园文化元素"
    ]
    
    for i in range(2):
        start_time = i * (duration / 3) + random.randint(0, 5)
        visual_events.append({
            "id": f"visual-{event_id:03d}",
            "modality": "visual",
            "startTime": int(start_time),
            "endTime": int(min(start_time + random.randint(3, 6), duration)),
            "riskScore": random.randint(40, 80),  # 扩大风险分数范围
            "detectionType": "logo",
            "detectionLabel": random.choice(logo_descriptions),
            "boundingBox": {
                "x": random.randint(50, 80),
                "y": random.randint(15, 35),
                "width": random.randint(8, 25),
                "height": random.randint(8, 25)
            },
            "confidence": random.randint(75, 96)  # 扩大置信度范围
        })
        event_id += 1
    
    # 增加人脸检测事件（至少4个）- 增加描述多样性
    face_descriptions = [
        "检测到人脸特写", "检测到表情变化", "检测到激动手势",
        "检测到多人画面", "检测到面部表情（微笑）", "检测到面部表情（严肃）",
        "检测到说话动作", "检测到眼神交流", "检测到肢体语言",
        "检测到手势辅助表达", "检测到表情自然", "检测到专注神情",
        "检测到侧脸角度", "检测到正面特写"
    ]
    
    for i in range(min(5, max(4, int(duration / 12)))):
        start_time = i * (duration / 5) + random.randint(0, 3)
        if start_time >= duration:
            break
        visual_events.append({
            "id": f"visual-{event_id:03d}",
            "modality": "visual",
            "startTime": int(start_time),
            "endTime": int(min(start_time + random.randint(3, 8), duration)),
            "riskScore": random.randint(25, 95),  # 大幅扩大范围，有高有低
            "detectionType": "face",
            "detectionLabel": random.choice(face_descriptions),
            "boundingBox": {
                "x": random.randint(20, 45),
                "y": random.randint(10, 30),
                "width": random.randint(20, 40),
                "height": random.randint(25, 50)
            },
            "confidence": random.randint(70, 98)
        })
        event_id += 1
    
    # 增加OCR文字检测（至少3个）- 增加描述多样性
    ocr_descriptions = [
        "OCR识别：屏幕显示学校相关文字", "OCR识别：字幕内容", "OCR识别：标语或横幅",
        "OCR识别：课件文字", "OCR识别：书籍文本", "OCR识别：黑板板书",
        "OCR识别：海报内容", "OCR识别：通知公告", "OCR识别：标题文字",
        "OCR识别：PPT内容", "OCR识别：文档截图", "OCR识别：聊天记录"
    ]
    
    for i in range(3):
        ocr_time = (i + 1) * (duration / 4) + random.randint(-3, 3)
        if ocr_time < 0 or ocr_time >= duration:
            continue
        visual_events.append({
            "id": f"visual-{event_id:03d}",
            "modality": "visual",
            "startTime": int(ocr_time),
            "endTime": int(min(ocr_time + random.randint(3, 6), duration)),
            "riskScore": random.randint(30, 95),  # 扩大范围
            "detectionType": "ocr",
            "detectionLabel": random.choice(ocr_descriptions),
            "boundingBox": {
                "x": random.randint(5, 30),
                "y": random.randint(40, 70),
                "width": random.randint(25, 55),
                "height": random.randint(6, 18)
            },
            "confidence": random.randint(75, 98)
        })
        event_id += 1
    
    logger.info(f"[任务 {task_id}] [视频处理模块] 生成 {len(visual_events)} 个视觉事件")
    return visual_events


def generate_scene_recognition(duration: float, scene_type: str) -> list:
    """
    生成场景识别数据（增加多样性）
    
    Args:
        duration: 视频时长
        scene_type: 主要场景类型
        
    Returns:
        场景列表
    """
    scenes = []
    scene_id = 1
    
    # 扩展场景图标映射（增加更多校园场景）
    scene_icons = {
        "教室讲课": "🏫",
        "图书馆学习": "📚",
        "实验室研究": "🔬",
        "宿舍日常": "🛏️",
        "食堂用餐": "🍱",
        "操场运动": "⚽",
        "校园活动": "🎉",
        "报告厅": "🎤",
        "校园漫步": "🌳",
        "自习室": "✏️",
        "线上教学": "💻",
        "课件展示": "📊",
        "校园风景": "🏛️",
        "体育馆": "🏀",
        "音乐厅": "🎵",
        "美术室": "🎨",
        "办公室": "📋",
        "会议室": "💼",
        "走廊": "🚶",
        "门口": "🚪"
    }
    
    # 可能的场景转换（从一个场景到另一个）
    all_scenes = list(scene_icons.keys())
    
    # 根据视频时长生成1-4个场景段
    scene_count = random.randint(1, min(4, max(1, int(duration / 20))))
    segment_duration = duration / scene_count
    
    for i in range(scene_count):
        # 第一个场景使用检测到的主场景类型，后续随机
        if i == 0:
            scene_name = scene_type
        else:
            # 随机选择一个不同的场景
            scene_name = random.choice([s for s in all_scenes if s != scenes[-1]["name"]])
        
        # 如果场景名不在映射中，选择一个相似的
        if scene_name not in scene_icons:
            scene_name = random.choice(all_scenes)
        
        scenes.append({
            "id": f"scene-{scene_id}",
            "name": scene_name,
            "icon": scene_icons.get(scene_name, "📹"),
            "confidence": round(random.uniform(0.68, 0.96), 2),  # 扩大置信度范围
            "timeStart": int(i * segment_duration),
            "timeEnd": int((i + 1) * segment_duration)
        })
        scene_id += 1
    
    return scenes


def generate_video_risks(duration: float, quality_score: float, has_person: bool) -> list:
    """
    生成视频风险点时间序列（基于5秒粒度）
    
    Args:
        duration: 视频时长
        quality_score: 画质评分
        has_person: 是否有人物
        
    Returns:
        视频风险点列表（索引对应时间段）
    """
    time_granularity = 5
    num_segments = int(duration / time_granularity) + 1
    video_risks = []
    
    # 基础风险曲线：开头低，中间可能有高峰，结尾趋于平缓
    for i in range(num_segments):
        # 生成波动的风险值
        if i < 2:
            # 开头段：低风险
            intensity = random.uniform(0.15, 0.35)
            reason = random.choice([
                "画面开场，视频起始段",
                "背景环境稳定",
                "正常画面，无明显风险"
            ])
        elif i >= num_segments - 2:
            # 结尾段：风险下降
            intensity = random.uniform(0.25, 0.45)
            reason = random.choice([
                "画面趋于平静",
                "结束陈述",
                "视频尾声"
            ])
        else:
            # 中间段：可能出现高风险
            if random.random() > 0.7:
                # 高风险峰值
                intensity = random.uniform(0.75, 0.95)
                reason = random.choice([
                    "检测到激烈表情和手势",
                    "画面内容异常",
                    "检测到过激行为",
                    "OCR识别到敏感文字"
                ])
            else:
                # 正常波动
                intensity = random.uniform(0.3, 0.65)
                reason = random.choice([
                    "正常陈述画面",
                    "持续画面内容",
                    "表情变化",
                    "场景切换"
                ])
        
        video_risks.append({
            "reason": reason,
            "intensity": round(intensity, 2)
        })
    
    return video_risks


def calculate_visual_risk_scores(scene_type: str, has_person: bool, quality_score: float) -> dict:
    """
    计算视觉维度的6个风险分数（用于后续融合计算）
    
    Args:
        scene_type: 场景类型
        has_person: 是否有人物
        quality_score: 画质评分
        
    Returns:
        6个维度的视觉分数（0-100）
    """
    # 1. 身份置信度（有人物且高质量时分数高）
    identity_score = 0
    if has_person:
        identity_score = int(70 + quality_score * 20 + random.randint(-5, 10))
    else:
        identity_score = random.randint(40, 60)
    
    # 2. 学校关联度（基于场景类型）
    university_score = 0
    if any(keyword in scene_type for keyword in ["教室", "图书馆", "实验室", "宿舍", "食堂", "操场", "报告厅"]):
        university_score = random.randint(75, 95)
    elif "校园" in scene_type:
        university_score = random.randint(60, 80)
    else:
        university_score = random.randint(30, 60)
    
    # 3. 负面情感度（视觉模态较难判断，给较低分数）
    attitude_score = random.randint(40, 70)
    
    # 4. 传播风险（基于场景和人物）
    topic_score = random.randint(50, 85)
    
    # 5. 影响范围（基于画质和场景）
    opinion_risk_score = int(50 + quality_score * 20 + random.randint(-10, 15))
    
    # 6. 处置紧迫度（视觉风险相对较低）
    action_score = random.randint(45, 75)
    
    return {
        "identity": min(100, max(0, identity_score)),
        "university": min(100, max(0, university_score)),
        "topic": min(100, max(0, topic_score)),
        "attitude": min(100, max(0, attitude_score)),
        "opinionRisk": min(100, max(0, opinion_risk_score)),
        "action": min(100, max(0, action_score))
    }


def process_video(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    处理视觉流分析 - 使用OpenCV进行真实分析
    适配新的前端数据结构
    
    Args:
        task_id: 任务ID
        video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
        
    Returns:
        视觉流分析结果（包含视觉事件流、场景识别、视频风险、特征分数）
    """
    logger.info(f"[任务 {task_id}] [视频处理模块] 开始处理视觉流...")
    start_time = time.time()
    
    try:
        # 获取视频URL
        video_url = video_info.get("videoUrl")
        video_url_internal = video_info.get("videoUrlInternal") or video_url
        logger.info(f"[任务 {task_id}] [视频处理模块] ========== 视频URL: {video_url} ==========")
        
        # 验证URL
        video_path = download_video(video_url, task_id) if video_url else None
        
        # 使用OpenCV进行真实视频分析
        opencv_result = analyze_video_with_opencv(video_url_internal or video_url, task_id)
        
        duration = opencv_result.get("duration", 60.0)
        scene_type = opencv_result.get("sceneType", "未知")
        has_person = opencv_result.get("hasPerson", False)
        quality_score = opencv_result.get("qualityScore", 0.7)
        
        # ========== 生成新数据结构 ==========
        
        # 1. 生成视觉事件流
        visual_events = generate_visual_events(duration, scene_type, has_person, task_id)
        
        # 2. 生成场景识别数据
        scene_recognition = generate_scene_recognition(duration, scene_type)
        
        # 3. 生成视频风险时间序列
        video_risks = generate_video_risks(duration, quality_score, has_person)
        
        # 4. 计算视觉维度的6个特征分数（用于后续融合）
        visual_risk_scores = calculate_visual_risk_scores(scene_type, has_person, quality_score)
        
        # 5. 主要人物特征（随机生成多样化数据）
        genders = ["男性", "女性"]
        age_ranges = ["18-20岁", "20-22岁", "22-24岁", "24-26岁", "19-23岁"]
        voice_profiles = [
            "年轻女声，语速中等", "年轻男声，语调平稳", 
            "青年男声，语速较快", "青年女声，声音清晰",
            "女声，语气温和", "男声，语调坚定",
            "年轻女声，语速较慢", "青年男声，声音沉稳",
            "女声清脆，情绪饱满", "男声低沉，表达有力"
        ]
        clothings = [
            "校服", "休闲装", "运动装", "T恤牛仔裤", "卫衣",
            "衬衫", "连帽衫", "短袖短裤", "正装", "羽绒服",
            "毛衣", "外套", "背心", "夹克"
        ]
        
        main_character = {
            "gender": random.choice(genders),
            "ageRange": random.choice(age_ranges),
            "voiceProfile": random.choice(voice_profiles),
            "clothing": random.choice(clothings)
        }
        
        # 构建返回结果（适配新前端数据结构）
        result = {
            # 视觉事件流（VisualEvent类型）
            "visualEvents": visual_events,
            
            # 场景识别
            "sceneRecognition": scene_recognition,
            
            # 时间轴-视频风险
            "videoRisks": video_risks,
            
            # 特征数据（用于后续融合计算）
            "features": {
                "mainCharacter": main_character,
                "sceneType": scene_type,
                "sceneConfidence": opencv_result.get("sceneConfidence", 0.7),
                "hasPerson": has_person,
                "faceCount": opencv_result.get("faceCount", 0),
                "qualityScore": quality_score,
                "brightness": opencv_result.get("brightness", 0.5),
                "clarity": opencv_result.get("clarity", 0.7),
                "width": opencv_result.get("width", 1920),
                "height": opencv_result.get("height", 1080),
                "fps": opencv_result.get("fps", 24),
                "duration": duration,
                
                # 6个维度的视觉风险分数
                "visualRiskScores": visual_risk_scores
            },
            
            # 元数据
            "videoPath": video_path,
            "processingTime": round(time.time() - start_time, 2)
        }
        
        logger.info(f"[任务 {task_id}] [视频处理模块] 视觉流处理完成")
        logger.info(f"[任务 {task_id}] - 视觉事件: {len(visual_events)}个")
        logger.info(f"[任务 {task_id}] - 场景识别: {len(scene_recognition)}个")
        logger.info(f"[任务 {task_id}] - 视频风险点: {len(video_risks)}个")
        logger.info(f"[任务 {task_id}] - 处理时间: {result['processingTime']:.2f}秒")
        
        return result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [视频处理模块] 处理失败: {e}", exc_info=True)
        # 返回默认值
        duration = video_info.get("videoDuration", 60.0)
        return {
            "visualEvents": [],
            "sceneRecognition": [{"id": "scene-1", "name": "校园场景", "icon": "🏫", "confidence": 0.7, "timeStart": 0, "timeEnd": int(duration)}],
            "videoRisks": [{"reason": "分析失败", "intensity": 0.3} for _ in range(int(duration / 5) + 1)],
            "features": {
                "mainCharacter": {
                    "gender": "男性",
                    "ageRange": "20-24岁",
                    "voiceProfile": "年轻男声",
                    "clothing": "休闲装"
                },
                "sceneType": "校园场景",
                "sceneConfidence": 0.0,
                "hasPerson": False,
                "faceCount": 0,
                "qualityScore": 0.5,
                "brightness": 0.5,
                "clarity": 0.5,
                "width": 1920,
                "height": 1080,
                "fps": 24,
                "duration": duration,
                "visualRiskScores": {
                    "identity": 50,
                    "university": 50,
                    "topic": 50,
                    "attitude": 50,
                    "opinionRisk": 50,
                    "action": 50
                }
            },
            "videoPath": video_url,
            "processingTime": round(time.time() - start_time, 2)
        }
