#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频处理模块
负责处理视觉流分析
开发者：潘嘉乐
"""

import time
import logging
import requests
import cv2
import numpy as np
from typing import Dict, Any, List

# 配置日志
logger = logging.getLogger(__name__)

_yolo_model = None
_yolo_init_attempted = False


def _get_optional_yolo_model():
    """按需加载 YOLO（可选依赖），未安装时自动降级。"""
    global _yolo_model, _yolo_init_attempted

    if _yolo_model is not None:
        return _yolo_model
    if _yolo_init_attempted:
        return None

    _yolo_init_attempted = True
    try:
        # 优先使用 YOLO-World（开放词汇检测），失败后回退普通YOLO
        try:
            from ultralytics import YOLOWorld  # type: ignore
            _yolo_model = YOLOWorld("yolov8s-worldv2.pt")
            logger.info("YOLO-World加载成功（yolov8s-worldv2.pt）")
        except Exception:
            from ultralytics import YOLO  # type: ignore
            _yolo_model = YOLO("yolov8n.pt")
            logger.info("YOLO通用模型加载成功（yolov8n.pt）")
    except Exception as e:
        logger.warning(f"YOLO未启用，自动降级到OpenCV规则分析: {e}")
        _yolo_model = None

    return _yolo_model


def _extract_wordpack_terms(word_packs: list) -> List[str]:
    terms: List[str] = []
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
                terms.append(t)
    return terms


def build_open_vocab_prompts(word_packs: list = None) -> List[str]:
    """构建 YOLO-World 开放词汇提示词。"""
    base_prompts = [
        "person", "crowd", "banner", "flag", "protest sign", "logo", "text",
        "classroom", "dormitory", "campus", "uniform", "student card"
    ]
    zh_terms = _extract_wordpack_terms(word_packs or [])

    prompts = []
    seen = set()
    for t in base_prompts + zh_terms:
        key = t.lower() if isinstance(t, str) else str(t)
        if key not in seen:
            seen.add(key)
            prompts.append(t)
    return prompts[:40]


def map_open_vocab_detections_to_risk(detections: List[dict]) -> Dict[str, Any]:
    """将开放词汇检测结果映射为可解释风险分。"""
    if not detections:
        return {"riskScore": 0.0, "riskFactors": []}

    keyword_weights = {
        "横幅": 0.35, "banner": 0.35,
        "人群聚集": 0.35, "crowd": 0.35,
        "protest": 0.45, "抗议": 0.45,
        "flag": 0.2, "logo": 0.15, "text": 0.1,
    }

    total = 0.0
    factors = []
    for d in detections:
        label = str(d.get("label", "")).lower()
        conf = float(d.get("confidence", 0.0))
        w = 0.0
        matched_key = None
        for k, kw in keyword_weights.items():
            if k.lower() in label:
                w = kw
                matched_key = k
                break
        if w <= 0:
            continue
        score = min(1.0, w * max(0.0, min(1.0, conf)) * 2)
        total += score
        factors.append({"label": d.get("label", ""), "confidence": round(conf, 3), "score": round(score, 3), "matched": matched_key})

    return {
        "riskScore": round(min(1.0, total), 3),
        "riskFactors": factors,
    }


def detect_objects_optional(frame: np.ndarray, prompts: List[str] = None) -> List[dict]:
    """
    可选 YOLO 目标检测：
    - 有 ultralytics 时返回检测结果
    - 无依赖时返回空列表（不影响主流程）
    """
    model = _get_optional_yolo_model()
    if model is None:
        return []

    try:
        # YOLO-World 支持 set_classes（开放词汇）
        if prompts and hasattr(model, "set_classes"):
            try:
                model.set_classes(prompts)
            except Exception:
                pass

        results = model(frame, verbose=False)
        detections = []
        for r in results:
            boxes = getattr(r, "boxes", None)
            if boxes is None:
                continue
            names = getattr(r, "names", {}) or {}
            for b in boxes:
                cls_id = int(b.cls[0]) if b.cls is not None else -1
                conf = float(b.conf[0]) if b.conf is not None else 0.0
                xyxy = b.xyxy[0].tolist() if b.xyxy is not None else [0, 0, 0, 0]
                detections.append({
                    "label": names.get(cls_id, str(cls_id)),
                    "confidence": round(conf, 4),
                    "bbox": [round(v, 2) for v in xyxy],
                })
        return detections
    except Exception as e:
        logger.warning(f"YOLO推理失败，已降级忽略: {e}")
        return []


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
            
            # 3. 基础风险值（基于画面统计特征，确定性）
            edge_density = cv2.Laplacian(gray, cv2.CV_64F).var()
            edge_risk = min(0.2, edge_density / 5000.0)
            base_risk = 0.08 + edge_risk
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
        # 返回明确失败态，不再伪造“正常默认值”
        return {
            "duration": 0.0,
            "width": 0,
            "height": 0,
            "fps": 0,
            "sceneType": "未知",
            "sceneConfidence": 0.0,
            "faceCount": 0,
            "hasPerson": False,
            "qualityScore": 0.0,
            "brightness": 0.0,
            "clarity": 0.0
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
            # 室内低色彩方差 → 教室/图书馆等（确定性）
            return "教室讲课"
        elif color_var < 90:
            # 室内中等色彩
            return "自习室"
        else:
            # 高色彩方差 → 户外
            return "校园活动"
    
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
        progress = t / max(1.0, duration)
        # 确定性曲线：中间略高，两端略低
        base_risk = 0.18 + max(0.0, 1 - abs(progress - 0.5) * 2) * 0.22
        time_series_data.append({
            "time": t,
            "risk": round(base_risk, 3)
        })
        
        # 确定性添加风险点
        if base_risk > 0.32:
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
        # 对预签名URL优先进行轻量可达性检查：
        # 某些 MinIO / 代理场景对 HEAD 不友好（会返回 403），但 GET 实际可读。
        try:
            response = requests.head(video_url, timeout=8, allow_redirects=True)
            if response.status_code == 200:
                logger.info(f"[任务 {task_id}] 视频URL验证成功(HEAD): {video_url}")
                return video_url
            if response.status_code in [401, 403, 405]:
                logger.warning(
                    f"[任务 {task_id}] 视频URL HEAD返回{response.status_code}，改用GET(range)复验"
                )
                try:
                    get_resp = requests.get(
                        video_url,
                        headers={"Range": "bytes=0-1"},
                        timeout=10,
                        stream=True,
                    )
                    if get_resp.status_code in [200, 206]:
                        logger.info(f"[任务 {task_id}] 视频URL验证成功(GET-range): {video_url}")
                        return video_url
                    logger.warning(f"[任务 {task_id}] 视频URL GET-range返回状态码: {get_resp.status_code}")
                except Exception as ge:
                    logger.warning(f"[任务 {task_id}] 视频URL GET-range验证失败: {ge}")
            else:
                logger.warning(f"[任务 {task_id}] 视频URL返回状态码: {response.status_code}")
            return video_url
        except Exception as head_err:
            logger.warning(f"[任务 {task_id}] 视频URL HEAD验证异常，继续尝试直接读取: {head_err}")
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
    
    # 生成logo检测事件（确定性）
    logo_descriptions = [
        "检测到教育机构相关标识", "识别到校徽图案", "检测到学校标志性建筑",
        "检测到学校名称logo", "识别到校园标志物", "检测到校训文字",
        "识别到教学楼铭牌", "检测到校园地标建筑", "识别到学院标识",
        "检测到学校宣传标语", "识别到校园文化元素"
    ]
    
    for i in range(2):
        start_time = i * (duration / 3)
        box_x = 50 + i * 10
        box_y = 20 + i * 5
        box_w = 16 + i * 2
        box_h = 12 + i * 2
        conf = 78 + i * 6
        risk = 42 + i * 10
        visual_events.append({
            "id": f"visual-{event_id:03d}",
            "modality": "visual",
            "startTime": int(start_time),
            "endTime": int(min(start_time + 4, duration)),
            "riskScore": min(95, risk),
            "detectionType": "logo",
            "detectionLabel": logo_descriptions[i % len(logo_descriptions)],
            "boundingBox": {
                "x": box_x,
                "y": box_y,
                "width": box_w,
                "height": box_h
            },
            "confidence": min(98, conf)
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
        start_time = i * (duration / 5)
        if start_time >= duration:
            break
        box_x = 20 + (i % 4) * 6
        box_y = 12 + (i % 3) * 6
        box_w = 24 + (i % 3) * 5
        box_h = 30 + (i % 4) * 4
        conf = 74 + i * 4
        base_risk = 30 + i * 10 + (8 if has_person else 0)
        visual_events.append({
            "id": f"visual-{event_id:03d}",
            "modality": "visual",
            "startTime": int(start_time),
            "endTime": int(min(start_time + 5, duration)),
            "riskScore": min(95, base_risk),
            "detectionType": "face",
            "detectionLabel": face_descriptions[i % len(face_descriptions)],
            "boundingBox": {
                "x": box_x,
                "y": box_y,
                "width": box_w,
                "height": box_h
            },
            "confidence": min(98, conf)
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
        ocr_time = (i + 1) * (duration / 4)
        if ocr_time < 0 or ocr_time >= duration:
            continue
        box_x = 8 + i * 7
        box_y = 45 + i * 8
        box_w = 30 + i * 8
        box_h = 10 + i * 2
        visual_events.append({
            "id": f"visual-{event_id:03d}",
            "modality": "visual",
            "startTime": int(ocr_time),
            "endTime": int(min(ocr_time + 4, duration)),
            "riskScore": min(95, 35 + i * 12),
            "detectionType": "ocr",
            "detectionLabel": ocr_descriptions[i % len(ocr_descriptions)],
            "boundingBox": {
                "x": box_x,
                "y": box_y,
                "width": box_w,
                "height": box_h
            },
            "confidence": min(98, 80 + i * 5)
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
    
    # 根据视频时长生成1-4个场景段（确定性）
    scene_count = min(4, max(1, int(duration / 25) + 1))
    segment_duration = duration / scene_count
    
    for i in range(scene_count):
        # 第一个场景使用检测到的主场景类型，后续随机
        if i == 0:
            scene_name = scene_type
        else:
            # 确定性选择一个不同场景
            prev = scenes[-1]["name"]
            next_candidates = [s for s in all_scenes if s != prev]
            scene_name = next_candidates[(i - 1) % len(next_candidates)] if next_candidates else prev
        
        # 如果场景名不在映射中，选择一个相似的
        if scene_name not in scene_icons:
            scene_name = all_scenes[i % len(all_scenes)]
        
        scenes.append({
            "id": f"scene-{scene_id}",
            "name": scene_name,
            "icon": scene_icons.get(scene_name, "📹"),
            "confidence": round(min(0.96, 0.74 + i * 0.06), 2),
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
    
    q = min(1.0, max(0.0, float(quality_score)))
    person_boost = 0.06 if has_person else 0.0
    # 基础风险曲线：确定性中间略高
    for i in range(num_segments):
        pos = i / max(1, num_segments - 1)
        center_boost = max(0.0, 1 - abs(pos - 0.5) * 2) * 0.28
        intensity = 0.18 + (1 - q) * 0.35 + person_boost + center_boost
        intensity = max(0.05, min(0.98, intensity))

        if intensity >= 0.75:
            reason = "画面信息密度高，存在较高视觉风险"
        elif intensity >= 0.5:
            reason = "检测到一般视觉风险波动"
        else:
            reason = "画面整体稳定"
        
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
    # 去随机化：采用可复现规则，保证“同输入同输出”
    q = min(1.0, max(0.0, float(quality_score)))

    # 1. 身份置信度：有人物且画质高时更容易提取身份线索
    identity_score = int((55 if has_person else 35) + q * (30 if has_person else 15))

    # 2. 学校关联度：由场景关键词直接驱动
    if any(keyword in scene_type for keyword in ["教室", "图书馆", "实验室", "宿舍", "食堂", "操场", "报告厅"]):
        university_base = 78
    elif "校园" in scene_type:
        university_base = 65
    else:
        university_base = 45
    university_score = int(university_base + q * 10)

    # 3. 负面情感度：视觉模态弱信号，主要看人物存在与画质不足
    attitude_score = int(30 + (1 - q) * 30 + (8 if has_person else 0))

    # 4. 主题分数：画质越高，主题可识别性越强
    topic_score = int(40 + q * 40 + (8 if has_person else 0))

    # 5. 舆论风险：画质越差，误判和异常内容风险越高
    opinion_risk_score = int(35 + (1 - q) * 45 + (5 if has_person else 0))

    # 6. 处置紧迫度：由舆论风险和态度综合决定
    action_score = int(opinion_risk_score * 0.65 + attitude_score * 0.35)
    
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
        
        # 验证URL：优先用内网可达地址，避免外网临时域名造成误报
        validate_url = video_url_internal or video_url
        video_path = download_video(validate_url, task_id) if validate_url else None
        
        # 使用OpenCV进行真实视频分析
        opencv_result = analyze_video_with_opencv(video_url_internal or video_url, task_id)
        
        duration = opencv_result.get("duration", 60.0)
        scene_type = opencv_result.get("sceneType", "未知")
        has_person = opencv_result.get("hasPerson", False)
        quality_score = opencv_result.get("qualityScore", 0.7)
        
        # ========== YOLO-World 开放词汇检测（可选） ==========
        open_vocab_prompts = build_open_vocab_prompts(video_info.get("wordPacks", []))
        open_vocab_detections = []
        try:
            cap = cv2.VideoCapture(video_url_internal or video_url)
            if cap.isOpened():
                fps = int(cap.get(cv2.CAP_PROP_FPS)) or 25
                frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
                sample_interval = max(1, fps * 5)
                max_samples = 8

                sample_idx = 0
                for frame_pos in range(0, frame_count, sample_interval):
                    if sample_idx >= max_samples:
                        break
                    cap.set(cv2.CAP_PROP_POS_FRAMES, frame_pos)
                    ret, frame = cap.read()
                    if not ret:
                        continue

                    ts = frame_pos / max(1, fps)
                    ds = detect_objects_optional(frame, prompts=open_vocab_prompts)
                    for d in ds:
                        d["timestamp"] = round(ts, 2)
                    open_vocab_detections.extend(ds)
                    sample_idx += 1
            cap.release()
        except Exception as e:
            logger.warning(f"[任务 {task_id}] YOLO-World开放词汇检测失败，降级继续: {e}")

        open_vocab_risk = map_open_vocab_detections_to_risk(open_vocab_detections)

        # ========== 生成新数据结构（尽量由真实检测结果驱动） ==========

        # 风险时间轴（基于真实帧统计）
        risk_timeline = analyze_video_frames_for_risk(video_url_internal or video_url, task_id)

        # 1) 视觉事件流：优先使用开放词汇检测真实框
        visual_events = []
        event_id = 1
        frame_w = max(1, int(opencv_result.get("width", 1)))
        frame_h = max(1, int(opencv_result.get("height", 1)))

        for d in open_vocab_detections[:16]:
            bbox = d.get("bbox", [0, 0, 0, 0])
            if len(bbox) == 4:
                x1, y1, x2, y2 = bbox
                box = {
                    "x": int(max(0, min(100, x1 / frame_w * 100))),
                    "y": int(max(0, min(100, y1 / frame_h * 100))),
                    "width": int(max(1, min(100, (x2 - x1) / frame_w * 100))),
                    "height": int(max(1, min(100, (y2 - y1) / frame_h * 100))),
                }
            else:
                box = {"x": 0, "y": 0, "width": 1, "height": 1}

            label = str(d.get("label", "object"))
            conf = float(d.get("confidence", 0.0))
            ts = int(float(d.get("timestamp", 0.0)))
            visual_events.append({
                "id": f"visual-{event_id:03d}",
                "modality": "visual",
                "startTime": max(0, min(int(duration), ts)),
                "endTime": max(0, min(int(duration), ts + 2)),
                "riskScore": int(max(0, min(100, conf * 100))),
                "detectionType": "open-vocab",
                "detectionLabel": label,
                "boundingBox": box,
                "confidence": int(max(0, min(100, conf * 100)))
            })
            event_id += 1

        # 若开放词汇检测为空，退回 OpenCV 人脸框（仍为真实检测）
        if not visual_events and (video_url_internal or video_url):
            try:
                cap = cv2.VideoCapture(video_url_internal or video_url)
                if cap.isOpened():
                    fps = int(cap.get(cv2.CAP_PROP_FPS)) or 25
                    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
                    sample_interval = max(1, fps * 5)
                    face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
                    for frame_pos in range(0, frame_count, sample_interval):
                        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_pos)
                        ret, frame = cap.read()
                        if not ret:
                            continue
                        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
                        faces = face_cascade.detectMultiScale(gray, 1.1, 4)
                        ts = int(frame_pos / max(1, fps))
                        for (x, y, w, h) in faces[:3]:
                            visual_events.append({
                                "id": f"visual-{event_id:03d}",
                                "modality": "visual",
                                "startTime": max(0, min(int(duration), ts)),
                                "endTime": max(0, min(int(duration), ts + 2)),
                                "riskScore": 55,
                                "detectionType": "face",
                                "detectionLabel": "OpenCV人脸检测",
                                "boundingBox": {
                                    "x": int(max(0, min(100, x / frame_w * 100))),
                                    "y": int(max(0, min(100, y / frame_h * 100))),
                                    "width": int(max(1, min(100, w / frame_w * 100))),
                                    "height": int(max(1, min(100, h / frame_h * 100))),
                                },
                                "confidence": 75
                            })
                            event_id += 1
                        if len(visual_events) >= 12:
                            break
                cap.release()
            except Exception as e:
                logger.warning(f"[任务 {task_id}] OpenCV人脸事件生成失败: {e}")

        # 2) 场景识别：单主场景（真实推断结果）
        scene_recognition = [{
            "id": "scene-1",
            "name": scene_type,
            "icon": "🏫" if "校园" in scene_type or "教室" in scene_type else "📹",
            "confidence": round(float(opencv_result.get("sceneConfidence", 0.0)), 2),
            "timeStart": 0,
            "timeEnd": int(duration)
        }] if duration > 0 else []

        # 3) 视频风险时间序列：由真实时间轴下采样为5s粒度
        video_risks = []
        ts_data = risk_timeline.get("timeSeriesData", [])
        if ts_data:
            for t in range(0, int(duration) + 1, 5):
                nearest = min(ts_data, key=lambda x: abs(float(x.get("time", 0.0)) - t))
                intensity = max(0.0, min(1.0, float(nearest.get("risk", 0.0))))
                if intensity >= 0.75:
                    reason = "画面风险较高"
                elif intensity >= 0.5:
                    reason = "画面风险中等"
                else:
                    reason = "画面整体稳定"
                video_risks.append({"reason": reason, "intensity": round(intensity, 2)})
        elif duration > 0:
            video_risks = [{"reason": "无可用视觉风险时间轴", "intensity": 0.0} for _ in range(int(duration / 5) + 1)]
        
        # 4. 计算视觉维度的6个特征分数（用于后续融合）
        visual_risk_scores = calculate_visual_risk_scores(scene_type, has_person, quality_score)

        # 将开放词汇检测风险注入视觉维度分数（可解释增强）
        ov_bonus = int(open_vocab_risk.get("riskScore", 0.0) * 25)
        visual_risk_scores["opinionRisk"] = min(100, visual_risk_scores["opinionRisk"] + ov_bonus)
        visual_risk_scores["action"] = min(100, visual_risk_scores["action"] + int(ov_bonus * 0.8))
        
        # 5. 主要人物特征（规则推断，不再随机）
        brightness = opencv_result.get("brightness", 0.0)
        clarity = opencv_result.get("clarity", 0.0)
        gender = "未知"
        age_range = "未知"
        if has_person:
            gender = "未确定"
            age_range = "18-25岁" if clarity >= 0.4 else "18-30岁"

        if "教室" in scene_type or "图书馆" in scene_type:
            clothing = "日常学习装"
        elif "操场" in scene_type or "运动" in scene_type:
            clothing = "运动装"
        else:
            clothing = "休闲装"

        voice_profile = "未知"
        if has_person:
            voice_profile = "语音画像由音频模块提供"

        main_character = {
            "gender": gender,
            "ageRange": age_range,
            "voiceProfile": voice_profile,
            "clothing": clothing
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
                "frameRiskTimeline": risk_timeline,
                
                # 6个维度的视觉风险分数
                "visualRiskScores": visual_risk_scores
            },

            "openVocab": {
                "engine": "YOLO-World(optional)",
                "prompts": open_vocab_prompts,
                "detections": open_vocab_detections,
                "risk": open_vocab_risk,
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
        # 返回失败态，避免伪造分析结果
        duration = video_info.get("videoDuration", 60.0)
        return {
            "visualEvents": [],
            "sceneRecognition": [],
            "videoRisks": [],
            "features": {
                "mainCharacter": {
                    "gender": "未知",
                    "ageRange": "未知",
                    "voiceProfile": "未知",
                    "clothing": "未知"
                },
                "sceneType": "未知",
                "sceneConfidence": 0.0,
                "hasPerson": False,
                "faceCount": 0,
                "qualityScore": 0.0,
                "brightness": 0.0,
                "clarity": 0.0,
                "width": 0,
                "height": 0,
                "fps": 0,
                "duration": duration,
                "frameRiskTimeline": {"timeSeriesData": [], "riskPoints": [], "duration": 0},
                "visualRiskScores": {
                    "identity": 0,
                    "university": 0,
                    "topic": 0,
                    "attitude": 0,
                    "opinionRisk": 0,
                    "action": 0
                }
            },
            "videoPath": video_info.get("videoUrl"),
            "processingTime": round(time.time() - start_time, 2)
        }
