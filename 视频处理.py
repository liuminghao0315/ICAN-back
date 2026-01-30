#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频处理模块 - 阿里云内容安全增强版
使用HTTP请求 + 签名机制调用阿里云API
与 test_video_moderation.py 使用同一套参数（仅 url + dataId），避免无效参数导致失败。
"""

import time
import random
import logging
from urllib.parse import urlparse
from typing import Dict, Any, Tuple

logger = logging.getLogger(__name__)

# ========================================================
# 【配置区】
# ========================================================
ALIYUN_ACCESS_KEY_ID = "LTAI5tMnc6fAwoqpaSd3pybw"
ALIYUN_ACCESS_KEY_SECRET = "GLYY1Jed0MqP86ZJKbkQoq5zEVMrcD"

# MinIO内网穿透公网地址（阿里云必须能通过公网访问该地址下载视频）
MINIO_PUBLIC_URL = "http://5aedd2d8.r12.cpolar.top"
MINIO_INTERNAL_URL = "http://192.168.6.130:9000"
# ========================================================


def _to_public_video_url(video_url: str) -> str:
    """
    将内网视频地址转为公网可访问地址（阿里云需能下载）。
    先按配置替换，若仍为内网则按 host 替换为 MINIO_PUBLIC_URL。
    """
    if not video_url or not video_url.strip():
        return ""
    url = video_url.strip()
    # 1) 配置的内网地址整体替换
    if MINIO_INTERNAL_URL and (url.startswith(MINIO_INTERNAL_URL) or url.startswith(MINIO_INTERNAL_URL.rstrip("/") + "/")):
        url = url.replace(MINIO_INTERNAL_URL, MINIO_PUBLIC_URL, 1)
    # 2) 其他内网形式：按 host 替换为公网
    parsed = urlparse(url)
    netloc = (parsed.netloc or "").lower()
    if "192.168." in netloc or "localhost" in netloc:
        base = MINIO_PUBLIC_URL.rstrip("/")
        path = ("/" + parsed.path.lstrip("/")) if parsed.path else ""
        url = base + path
    return url


def _is_url_ok_for_aliyun(url: str) -> Tuple[bool, str]:
    """校验 URL 是否适合交给阿里云（公网、长度、无中文）。返回 (是否可用, 错误信息)。"""
    if not url or not url.strip():
        return False, "视频地址为空"
    if len(url) > 2048:
        return False, "视频地址超过2048字符"
    try:
        url.encode("ascii")
    except UnicodeEncodeError:
        return False, "视频地址不能包含中文，请使用英文路径或公网URL"
    parsed = urlparse(url)
    if "192.168." in (parsed.netloc or "").lower() or "localhost" in (parsed.netloc or "").lower():
        return False, "视频地址必须是公网可访问的URL，当前为内网地址"
    if not url.startswith("http://") and not url.startswith("https://"):
        return False, "视频地址必须以 http:// 或 https:// 开头"
    return True, ""


def process_video(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    处理视觉流分析 - 调用阿里云视频审核API
    
    Args:
        task_id: 任务ID
        video_info: 视频信息（需包含 videoUrl，且最终为公网可访问地址）
        
    Returns:
        分析结果（符合前端期望的格式）
    """
    print(f"\n{'='*60}")
    print(f"[视频处理] 任务开始: {task_id}")
    print(f"{'='*60}")
    
    start_time = time.time()
    video_url = video_info.get("videoUrl", "") or video_info.get("video_url", "")
    
    # 内网地址转公网（与测试脚本一致：只传阿里云能下载的 URL）
    public_url = _to_public_video_url(video_url)
    print(f"[视频处理] 原始URL: {(video_url or '')[:80]}...")
    print(f"[视频处理] 公网URL: {(public_url or '')[:80]}...")
    
    ok, err_msg = _is_url_ok_for_aliyun(public_url)
    if not ok:
        process_time = time.time() - start_time
        print(f"[视频处理] 跳过审核: {err_msg}")
        return {
            "duration": video_info.get("videoDuration", 60.0),
            "width": 1920,
            "height": 1080,
            "fps": 30,
            "sceneType": "未知",
            "sceneConfidence": 0,
            "faceCount": 0,
            "hasPerson": False,
            "qualityScore": 0.5,
            "brightness": 0.5,
            "clarity": 0.5,
            "processingTime": round(process_time, 2),
            "moderation_passed": False,
            "moderation_label": "error",
            "moderation_confidence": 0,
            "moderation_violations": [err_msg],
            "moderation_suggestion": "review",
        }
    
    try:
        # 与 test_video_moderation.py 一致：同一 region，仅 url + dataId，不传 scenes
        from aliyun_green_client import AliyunGreenClient
        client = AliyunGreenClient(
            ALIYUN_ACCESS_KEY_ID,
            ALIYUN_ACCESS_KEY_SECRET,
            region="cn-shanghai",
        )
        
        # 调用视频审核（参数与成功测试一致：无 scenes，轮询间隔 10 秒）
        print(f"[视频处理] 开始调用阿里云视频审核...")
        moderation = client.video_scan_sync(
            video_url=public_url,
            data_id=task_id,
            poll_interval=10,
            max_wait=300,
        )
        
        process_time = time.time() - start_time
        print(f"[视频处理] 审核完成! 耗时: {process_time:.1f}秒")
        print(f"[视频处理] 审核结果: {moderation}")
        
        # 构建返回结果
        result = {
            # 基础视频信息
            "duration": video_info.get("videoDuration", 60.0),
            "width": 1920,
            "height": 1080,
            "fps": 30,
            
            # 场景分析（模拟数据，后续可接入其他AI）
            "sceneType": random.choice(["教室", "图书馆", "操场", "宿舍", "食堂", "实验室"]),
            "sceneConfidence": round(random.uniform(0.7, 1.0), 4),
            "faceCount": random.randint(0, 5),
            "hasPerson": True,
            "qualityScore": round(random.uniform(0.7, 1.0), 4),
            "brightness": round(random.uniform(0.4, 0.6), 4),
            "clarity": round(random.uniform(0.7, 1.0), 4),
            
            # 处理时间
            "processingTime": round(process_time, 2),
            
            # ===== 审核结果（核心字段）=====
            "moderation_passed": moderation["passed"],
            "moderation_label": moderation["label"],
            "moderation_confidence": moderation["confidence"],
            "moderation_violations": moderation["violations"],
            "moderation_suggestion": moderation["suggestion"],
        }
        
        print(f"[视频处理] 返回数据: {result}")
        return result
        
    except Exception as e:
        process_time = time.time() - start_time
        print(f"[视频处理] 出错: {e}")
        import traceback
        traceback.print_exc()
        
        return {
            "duration": video_info.get("videoDuration", 60.0),
            "width": 1920,
            "height": 1080,
            "fps": 30,
            "sceneType": "未知",
            "sceneConfidence": 0,
            "faceCount": 0,
            "hasPerson": False,
            "qualityScore": 0.5,
            "brightness": 0.5,
            "clarity": 0.5,
            "processingTime": round(process_time, 2),
            "moderation_passed": False,
            "moderation_label": "error",
            "moderation_confidence": 0,
            "moderation_violations": [str(e)],
            "moderation_suggestion": "review",
        }
