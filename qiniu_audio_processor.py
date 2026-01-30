#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
七牛云音频审核模块
使用七牛云API进行音频内容审核
"""

import time
import logging
from typing import Dict, Any

from qiniu_config import QiniuConfig, get_qiniu_client

# 配置日志
logger = logging.getLogger(__name__)


class QiniuAudioProcessor:
    """七牛云音频审核处理器"""
    
    def __init__(self):
        """初始化音频审核处理器"""
        self.client = get_qiniu_client()
        self.api_url = QiniuConfig.AUDIO_CENSOR_URL
    
    def process_audio(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        使用七牛云API处理音频审核
        
        Args:
            task_id: 任务ID
            video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
            
        Returns:
            音频审核结果
        """
        logger.info(f"[任务 {task_id}] [七牛云音频审核] 开始处理音频...")
        
        try:
            # 检查客户端是否可用
            if self.client is None:
                logger.error(f"[任务 {task_id}] [七牛云音频审核] 七牛云客户端未初始化")
                return self._create_fallback_result(task_id, video_info, "七牛云客户端未初始化")
            
            # 获取视频URL（假设音频与视频在同一文件中）
            video_url = video_info.get("videoUrl")
            if not video_url:
                logger.error(f"[任务 {task_id}] [七牛云音频审核] 视频URL为空")
                return self._create_fallback_result(task_id, video_info, "视频URL为空")
            
            # 创建审核参数
            params = QiniuConfig.create_audio_censor_params(video_url, task_id)
            
            # 调用七牛云API
            start_time = time.time()
            api_result = self.client.call_api(self.api_url, params)
            processing_time = time.time() - start_time
            
            # 解析API结果
            result = self._parse_audio_result(api_result, video_info, processing_time)
            
            logger.info(f"[任务 {task_id}] [七牛云音频审核] 音频审核完成，处理时间: {processing_time:.2f}秒")
            return result
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [七牛云音频审核] 处理失败: {e}", exc_info=True)
            return self._create_fallback_result(task_id, video_info, str(e))
    
    def _parse_audio_result(self, api_result: Dict[str, Any], 
                           video_info: Dict[str, Any], 
                           processing_time: float) -> Dict[str, Any]:
        """
        解析七牛云音频审核结果
        
        Args:
            api_result: API返回的原始结果
            video_info: 视频信息
            processing_time: 处理时间
            
        Returns:
            格式化后的审核结果
        """
        # 提取审核结果
        result_data = api_result.get('result', {})
        censor_result = result_data.get('censor', {})
        
        # 解析场景审核结果
        scenes_result = censor_result.get('scenes', {})
        
        # 计算总体风险评分
        risk_score = self._calculate_audio_risk_score(scenes_result)
        
        # 提取具体场景的审核结果
        antispam_result = scenes_result.get('antispam', {})
        
        # 构建返回结果
        result = {
            # 音频基础信息
            "hasAudio": True,
            "audioDuration": video_info.get("videoDuration", 0),
            
            # 审核结果
            "riskScore": risk_score,
            "riskLevel": self._get_risk_level(risk_score),
            "hasViolation": risk_score > 0.7,
            
            # 反垃圾审核结果
            "antispamScore": antispam_result.get('score', 0),
            "antispamLabel": antispam_result.get('label', 'normal'),
            "antispamSuggestion": antispam_result.get('suggestion', 'pass'),
            
            # 音频特征（模拟）
            "audioQuality": self._estimate_audio_quality(video_info),
            "speechRatio": 0.6,  # 模拟值
            "musicRatio": 0.2,  # 模拟值
            "noiseLevel": 0.1,  # 模拟值
            "volumeLevel": 0.7,  # 模拟值
            "emotionInVoice": "neutral",  # 模拟值
            
            # 处理信息
            "processingTime": round(processing_time, 2),
            "apiJobId": api_result.get('id', ''),
            "apiStatus": api_result.get('status', 'unknown'),
            "source": "qiniu_audio_censor"
        }
        
        return result
    
    def _calculate_audio_risk_score(self, scenes_result: Dict[str, Any]) -> float:
        """
        计算音频总体风险评分
        
        Args:
            scenes_result: 各场景审核结果
            
        Returns:
            风险评分 (0-1)
        """
        max_score = 0.0
        
        for scene_name, scene_result in scenes_result.items():
            if isinstance(scene_result, dict):
                score = scene_result.get('score', 0)
                suggestion = scene_result.get('suggestion', 'pass')
                
                # 如果建议为block或review，风险更高
                if suggestion in ['block', 'review']:
                    score = max(score, 0.7)
                
                max_score = max(max_score, score)
        
        return round(max_score, 4)
    
    def _get_risk_level(self, risk_score: float) -> str:
        """
        根据风险评分获取风险等级
        
        Args:
            risk_score: 风险评分
            
        Returns:
            风险等级
        """
        if risk_score < 0.3:
            return "LOW"
        elif risk_score < 0.7:
            return "MEDIUM"
        else:
            return "HIGH"
    
    def _estimate_audio_quality(self, video_info: Dict[str, Any]) -> float:
        """
        估计音频质量（模拟函数）
        
        Args:
            video_info: 视频信息
            
        Returns:
            质量评分 (0-1)
        """
        # 这里可以根据文件大小、时长等信息估计质量
        # 目前返回一个模拟值
        file_size = video_info.get("fileSize", 0)
        duration = video_info.get("videoDuration", 0)
        
        if file_size > 0 and duration > 0:
            # 简单的比特率估算
            bitrate = file_size * 8 / duration  # bps
            if bitrate > 1000000:  # 1 Mbps
                return 0.8
            elif bitrate > 500000:  # 500 kbps
                return 0.6
            else:
                return 0.4
        
        return 0.5
    
    def _create_fallback_result(self, task_id: str, video_info: Dict[str, Any], 
                               error_msg: str) -> Dict[str, Any]:
        """
        创建回退结果（当API调用失败时）
        
        Args:
            task_id: 任务ID
            video_info: 视频信息
            error_msg: 错误信息
            
        Returns:
            回退结果
        """
        logger.warning(f"[任务 {task_id}] [七牛云音频审核] 使用回退结果: {error_msg}")
        
        return {
            "hasAudio": True,
            "audioDuration": video_info.get("videoDuration", 0),
            "riskScore": 0.0,
            "riskLevel": "LOW",
            "hasViolation": False,
            "audioQuality": 0.5,
            "speechRatio": 0.6,
            "musicRatio": 0.2,
            "noiseLevel": 0.1,
            "volumeLevel": 0.7,
            "emotionInVoice": "neutral",
            "processingTime": 0.0,
            "apiJobId": "",
            "apiStatus": "failed",
            "error": error_msg,
            "source": "fallback"
        }


# 兼容旧接口的函数
def extract_audio_features(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    提取音频特征（兼容旧接口）
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        
    Returns:
        音频特征分析结果
    """
    processor = QiniuAudioProcessor()
    return processor.process_audio(task_id, video_info)


if __name__ == "__main__":
    # 测试代码
    import json
    
    # 配置日志
    logging.basicConfig(level=logging.INFO)
    
    # 测试数据
    test_task_id = "test_audio_001"
    test_video_info = {
        "videoUrl": "http://example.com/test.mp4",
        "videoTitle": "测试音频",
        "videoDuration": 120.5,
        "fileSize": 10240000  # 10MB
    }
    
    # 创建处理器
    processor = QiniuAudioProcessor()
    
    # 测试处理
    print("开始测试七牛云音频审核...")
    try:
        result = processor.process_audio(test_task_id, test_video_info)
        print(f"测试结果: {json.dumps(result, indent=2, ensure_ascii=False)}")
    except Exception as e:
        print(f"测试失败: {e}")