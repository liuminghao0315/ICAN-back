#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
七牛云文本审核模块
使用七牛云API进行文本内容审核
"""

import time
import logging
from typing import Dict, Any

from qiniu_config import QiniuConfig, get_qiniu_client

# 配置日志
logger = logging.getLogger(__name__)


class QiniuTextProcessor:
    """七牛云文本审核处理器"""
    
    def __init__(self):
        """初始化文本审核处理器"""
        self.client = get_qiniu_client()
        self.api_url = QiniuConfig.TEXT_CENSOR_URL
    
    def process_text(self, task_id: str, text: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        使用七牛云API处理文本审核
        
        Args:
            task_id: 任务ID
            text: 待审核文本
            video_info: 视频信息（用于上下文）
            
        Returns:
            文本审核结果
        """
        logger.info(f"[任务 {task_id}] [七牛云文本审核] 开始处理文本...")
        
        try:
            # 检查客户端是否可用
            if self.client is None:
                logger.error(f"[任务 {task_id}] [七牛云文本审核] 七牛云客户端未初始化")
                return self._create_fallback_result(task_id, video_info, "七牛云客户端未初始化")
            
            # 检查文本是否为空
            if not text or not text.strip():
                logger.warning(f"[任务 {task_id}] [七牛云文本审核] 文本为空，使用视频标题作为替代")
                text = video_info.get("videoTitle", "")
            
            # 创建审核参数
            params = QiniuConfig.create_text_censor_params(text, task_id)
            
            # 调用七牛云API
            start_time = time.time()
            api_result = self.client.call_api(self.api_url, params)
            processing_time = time.time() - start_time
            
            # 解析API结果
            result = self._parse_text_result(api_result, text, video_info, processing_time)
            
            logger.info(f"[任务 {task_id}] [七牛云文本审核] 文本审核完成，处理时间: {processing_time:.2f}秒")
            return result
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [七牛云文本审核] 处理失败: {e}", exc_info=True)
            return self._create_fallback_result(task_id, video_info, str(e))
    
    def _parse_text_result(self, api_result: Dict[str, Any], 
                          text: str, 
                          video_info: Dict[str, Any], 
                          processing_time: float) -> Dict[str, Any]:
        """
        解析七牛云文本审核结果
        
        Args:
            api_result: API返回的原始结果
            text: 审核的文本
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
        risk_score = self._calculate_text_risk_score(scenes_result)
        
        # 提取具体场景的审核结果
        antispam_result = scenes_result.get('antispam', {})
        
        # 构建返回结果
        result = {
            # 文本基础信息
            "textLength": len(text),
            "originalText": text[:100] + "..." if len(text) > 100 else text,
            "hasText": len(text.strip()) > 0,
            
            # 审核结果
            "riskScore": risk_score,
            "riskLevel": self._get_risk_level(risk_score),
            "hasViolation": risk_score > 0.7,
            
            # 反垃圾审核结果
            "antispamScore": antispam_result.get('score', 0),
            "antispamLabel": antispam_result.get('label', 'normal'),
            "antispamSuggestion": antispam_result.get('suggestion', 'pass'),
            
            # 文本特征分析
            "sentimentScore": self._analyze_sentiment(text),
            "sentimentLabel": self._get_sentiment_label(text),
            "topicCategory": self._detect_topic(text),
            "keywords": self._extract_keywords(text),
            "languageConfidence": 0.95,  # 假设中文文本
            
            # 处理信息
            "processingTime": round(processing_time, 2),
            "apiJobId": api_result.get('id', ''),
            "apiStatus": api_result.get('status', 'unknown'),
            "source": "qiniu_text_censor"
        }
        
        return result
    
    def _calculate_text_risk_score(self, scenes_result: Dict[str, Any]) -> float:
        """
        计算文本总体风险评分
        
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
    
    def _analyze_sentiment(self, text: str) -> float:
        """
        分析文本情感（模拟函数）
        
        Args:
            text: 文本
            
        Returns:
            情感评分 (-1到1)
        """
        # 简单的关键词匹配
        positive_words = ['好', '优秀', '棒', '喜欢', '支持', '感谢', '成功']
        negative_words = ['差', '糟糕', '不好', '讨厌', '反对', '失败', '问题']
        
        score = 0.0
        for word in positive_words:
            if word in text:
                score += 0.1
        
        for word in negative_words:
            if word in text:
                score -= 0.1
        
        # 限制在-1到1之间
        return max(-1.0, min(1.0, score))
    
    def _get_sentiment_label(self, text: str) -> str:
        """
        获取情感标签
        
        Args:
            text: 文本
            
        Returns:
            情感标签
        """
        sentiment_score = self._analyze_sentiment(text)
        
        if sentiment_score > 0.3:
            return "POSITIVE"
        elif sentiment_score < -0.3:
            return "NEGATIVE"
        else:
            return "NEUTRAL"
    
    def _detect_topic(self, text: str) -> str:
        """
        检测文本主题（模拟函数）
        
        Args:
            text: 文本
            
        Returns:
            主题分类
        """
        # 简单的关键词匹配
        topic_keywords = {
            "校园生活": ['学生', '校园', '老师', '学习', '考试'],
            "学术讨论": ['研究', '论文', '学术', '科学', '实验'],
            "社团活动": ['社团', '活动', '组织', '团队', '合作'],
            "体育运动": ['运动', '比赛', '体育', '健身', '锻炼'],
            "科技创新": ['科技', '创新', '技术', '开发', '编程']
        }
        
        for topic, keywords in topic_keywords.items():
            for keyword in keywords:
                if keyword in text:
                    return topic
        
        return "其他"
    
    def _extract_keywords(self, text: str, max_keywords: int = 5) -> list:
        """
        提取关键词（模拟函数）
        
        Args:
            text: 文本
            max_keywords: 最大关键词数量
            
        Returns:
            关键词列表
        """
        # 常见关键词库
        common_keywords = [
            '大学生', '校园', '学习', '考试', '社团', '青春', '梦想', '奋斗',
            '创新', '创业', '实习', '就业', '考研', '保研', '留学', '志愿者',
            '比赛', '获奖', '论文', '实验', '项目', '团队', '合作', '成长'
        ]
        
        # 找出文本中出现的关键词
        found_keywords = []
        for keyword in common_keywords:
            if keyword in text:
                found_keywords.append(keyword)
                if len(found_keywords) >= max_keywords:
                    break
        
        return found_keywords
    
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
        logger.warning(f"[任务 {task_id}] [七牛云文本审核] 使用回退结果: {error_msg}")
        
        text = video_info.get("videoTitle", "")
        
        return {
            "textLength": len(text),
            "originalText": text[:100] + "..." if len(text) > 100 else text,
            "hasText": len(text.strip()) > 0,
            "riskScore": 0.0,
            "riskLevel": "LOW",
            "hasViolation": False,
            "sentimentScore": 0.0,
            "sentimentLabel": "NEUTRAL",
            "topicCategory": "其他",
            "keywords": [],
            "languageConfidence": 0.9,
            "processingTime": 0.0,
            "apiJobId": "",
            "apiStatus": "failed",
            "error": error_msg,
            "source": "fallback"
        }


# 兼容旧接口的函数
def convert_audio_to_text(task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    音频转文本审核（兼容旧接口）
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        
    Returns:
        文本分析结果
    """
    processor = QiniuTextProcessor()
    
    # 这里假设视频标题作为待审核文本
    # 在实际应用中，可能需要从音频中提取文本
    text = video_info.get("videoTitle", "")
    
    return processor.process_text(task_id, text, video_info)


if __name__ == "__main__":
    # 测试代码
    import json
    
    # 配置日志
    logging.basicConfig(level=logging.INFO)
    
    # 测试数据
    test_task_id = "test_text_001"
    test_text = "这是一段测试文本，包含正常的校园生活内容。"
    test_video_info = {
        "videoUrl": "http://example.com/test.mp4",
        "videoTitle": "校园生活分享",
        "videoDuration": 120.5,
        "fileSize": 10240000  # 10MB
    }
    
    # 创建处理器
    processor = QiniuTextProcessor()
    
    # 测试处理
    print("开始测试七牛云文本审核...")
    try:
        result = processor.process_text(test_task_id, test_text, test_video_info)
        print(f"测试结果: {json.dumps(result, indent=2, ensure_ascii=False)}")
    except Exception as e:
        print(f"测试失败: {e}")