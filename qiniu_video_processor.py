#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
七牛云视频审核模块
使用七牛云API进行视频内容审核
"""

import time
import logging
from typing import Dict, Any

from qiniu_config import QiniuConfig, get_qiniu_client

# 配置日志
logger = logging.getLogger(__name__)


class QiniuVideoProcessor:
    """七牛云视频审核处理器"""
    
    def __init__(self):
        """初始化视频审核处理器"""
        self.client = get_qiniu_client()
        self.api_url = QiniuConfig.VIDEO_CENSOR_URL
    
    def process_video(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        使用七牛云API处理视频审核
        
        Args:
            task_id: 任务ID
            video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
            
        Returns:
            视频审核结果
        """
        logger.info(f"[任务 {task_id}] [七牛云视频审核] 开始处理视频...")
        
        try:
            # 检查客户端是否可用
            if self.client is None:
                logger.error(f"[任务 {task_id}] [七牛云视频审核] 七牛云客户端未初始化")
                return self._create_fallback_result(task_id, video_info, "七牛云客户端未初始化")
            
            # 获取视频URL
            video_url = video_info.get("videoUrl")
            if not video_url:
                logger.error(f"[任务 {task_id}] [七牛云视频审核] 视频URL为空")
                return self._create_fallback_result(task_id, video_info, "视频URL为空")
            
            # 创建审核参数
            params = QiniuConfig.create_video_censor_params(video_url, task_id)
            
            # 调用七牛云API
            start_time = time.time()
            api_result = self.client.call_api(self.api_url, params)
            processing_time = time.time() - start_time
            
            # 解析API结果
            result = self._parse_video_result(api_result, video_info, processing_time)
            
            logger.info(f"[任务 {task_id}] [七牛云视频审核] 视频审核完成，处理时间: {processing_time:.2f}秒")
            return result
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [七牛云视频审核] 处理失败: {e}", exc_info=True)
            return self._create_fallback_result(task_id, video_info, str(e))
    
    def _parse_video_result(self, api_result: Dict[str, Any], 
                           video_info: Dict[str, Any], 
                           processing_time: float) -> Dict[str, Any]:
        """
        解析七牛云视频审核结果
        
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
        risk_score = self._calculate_video_risk_score(scenes_result)
        
        # 提取具体场景的审核结果
        pulp_result = scenes_result.get('pulp', {})
        terror_result = scenes_result.get('terror', {})
        politician_result = scenes_result.get('politician', {})
        ads_result = scenes_result.get('ads', {})
        
        # 构建返回结果
        result = {
            # 基础信息
            "duration": video_info.get("videoDuration", 0),
            "width": 1920,  # 默认值，实际应从视频元数据获取
            "height": 1080,  # 默认值
            "fps": 30,  # 默认值
            
            # 审核结果
            "riskScore": risk_score,
            "riskLevel": self._get_risk_level(risk_score),
            "hasViolation": risk_score > 0.7,
            
            # 各场景审核结果
            "pulpScore": pulp_result.get('score', 0),
            "pulpLabel": pulp_result.get('label', 'normal'),
            "pulpSuggestion": pulp_result.get('suggestion', 'pass'),
            
            "terrorScore": terror_result.get('score', 0),
            "terrorLabel": terror_result.get('label', 'normal'),
            "terrorSuggestion": terror_result.get('suggestion', 'pass'),
            
            "politicianScore": politician_result.get('score', 0),
            "politicianLabel": politician_result.get('label', 'normal'),
            "politicianSuggestion": politician_result.get('suggestion', 'pass'),
            
            "adsScore": ads_result.get('score', 0),
            "adsLabel": ads_result.get('label', 'normal'),
            "adsSuggestion": ads_result.get('suggestion', 'pass'),
            
            # 视频质量评估（模拟）
            "qualityScore": self._estimate_quality(video_info),
            "brightness": 0.5,  # 模拟值
            "clarity": 0.8,  # 模拟值
            
            # 处理信息
            "processingTime": round(processing_time, 2),
            "apiJobId": api_result.get('id', ''),
            "apiStatus": api_result.get('status', 'unknown'),
            "source": "qiniu_video_censor"
        }
        
        return result
    
    def _calculate_video_risk_score(self, scenes_result: Dict[str, Any]) -> float:
        """
        计算视频总体风险评分
        
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
    
    def _estimate_quality(self, video_info: Dict[str, Any]) -> float:
        """
        估计视频质量（模拟函数）
        
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
            if bitrate > 2000000:  # 2 Mbps
                return 0.9
            elif bitrate > 1000000:  # 1 Mbps
                return 0.7
            else:
                return 0.5
        
        return 0.6
    
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
        logger.warning(f"[任务 {task_id}] [七牛云视频审核] 使用回退结果: {error_msg}")
        
        return {
            "duration": video_info.get("videoDuration", 0),
            "width": 1920,
            "height": 1080,
            "fps": 30,
            "riskScore": 0.0,
            "riskLevel": "LOW",
            "hasViolation": False,
            "qualityScore": 0.6,
            "brightness": 0.5,
            "clarity": 0.8,
            "processingTime": 0.0,
            "apiJobId": "",
            "apiStatus": "failed",
            "error": error_msg,
            "source": "fallback"
        }


# 兼容旧接口的函数
def process_video(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    处理视频审核（兼容旧接口）
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        
    Returns:
        视频审核结果
    """
    processor = QiniuVideoProcessor()
    return processor.process_video(task_id, video_info)


if __name__ == "__main__":
    # 测试代码
    import json
    
    # 配置日志
    logging.basicConfig(level=logging.INFO)
    
    # 测试数据
    test_task_id = "test_video_001"
    test_video_info = {
        "videoUrl": "http://example.com/test.mp4",
        "videoTitle": "测试视频",
        "videoDuration": 120.5,
        "fileSize": 10240000  # 10MB
    }
    
    # 创建处理器
    processor = QiniuVideoProcessor()
    
    # 测试处理
    print("开始测试七牛云视频审核...")
    try:
        result = processor.process_video(test_task_id, test_video_info)
        print(f"测试结果: {json.dumps(result, indent=2, ensure_ascii=False)}")
    except Exception as e:
        print(f"测试失败: {e}")