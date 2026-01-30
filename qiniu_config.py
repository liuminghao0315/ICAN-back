#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
七牛云配置模块
包含七牛云API的配置信息和工具函数
支持从 .env 文件加载配置
"""

import os
import logging
from typing import Dict, Any, Optional

# 配置日志
logger = logging.getLogger(__name__)


def load_env_file(env_file: str = '.env') -> bool:
    """
    从 .env 文件加载环境变量
    
    Args:
        env_file: 环境变量文件路径
        
    Returns:
        是否成功加载
    """
    try:
        if not os.path.exists(env_file):
            logger.warning(f"环境变量文件不存在: {env_file}")
            return False
        
        with open(env_file, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                # 跳过空行和注释
                if not line or line.startswith('#'):
                    continue
                
                # 解析 key=value
                if '=' in line:
                    key, value = line.split('=', 1)
                    key = key.strip()
                    value = value.strip()
                    
                    # 移除引号
                    if (value.startswith('"') and value.endswith('"')) or \
                       (value.startswith("'") and value.endswith("'")):
                        value = value[1:-1]
                    
                    
                    # 设置环境变量（如果尚未设置）
                    if key and value and key not in os.environ:
                        os.environ[key] = value
                        logger.debug(f"从 .env 文件加载环境变量: {key}")
        
        logger.info(f"成功从 .env 文件加载环境变量")
        return True
        
    except Exception as e:
        logger.error(f"加载 .env 文件失败: {e}")
        return False


# 加载 .env 文件
load_env_file()


class QiniuConfig:
    """七牛云配置类"""
    
    # 七牛云API配置 - 从环境变量读取
    ACCESS_KEY = os.environ.get('QINIU_ACCESS_KEY', 'your_access_key_here')
    SECRET_KEY = os.environ.get('QINIU_SECRET_KEY', 'your_secret_key_here')
    
    # 审核API端点
    VIDEO_CENSOR_URL = 'http://ai.qiniuapi.com/v3/video/censor'
    AUDIO_CENSOR_URL = 'http://ai.qiniuapi.com/v3/audio/censor'
    TEXT_CENSOR_URL = 'http://ai.qiniuapi.com/v3/text/censor'
    
    # 回调URL（用于异步审核结果回调）
    # 这里设置为本地测试URL，实际部署时需要替换为公网可访问的URL
    CALLBACK_URL = os.environ.get('QINIU_CALLBACK_URL', 'http://localhost:8080/api/algorithm/callback')
    
    # 审核参数配置
    DEFAULT_CONFIG = {
        # 视频审核配置
        'video': {
            'scenes': ['pulp', 'terror', 'politician', 'ads'],  # 审核场景
            'async': True,  # 异步审核
            'callback': CALLBACK_URL,
            'callback_body': '{"taskId":"$(x:taskId)","moduleType":"video","resultData":$(avinfo)}',
            'callback_body_type': 'application/json'
        },
        # 音频审核配置
        'audio': {
            'scenes': ['antispam'],  # 反垃圾审核
            'async': True,
            'callback': CALLBACK_URL,
            'callback_body': '{"taskId":"$(x:taskId)","moduleType":"audio","resultData":$(avinfo)}',
            'callback_body_type': 'application/json'
        },
        # 文本审核配置
        'text': {
            'scenes': ['antispam'],  # 反垃圾审核
            'async': True,
            'callback': CALLBACK_URL,
            'callback_body': '{"taskId":"$(x:taskId)","moduleType":"text","resultData":$(avinfo)}',
            'callback_body_type': 'application/json'
        }
    }
    
    @classmethod
    def get_config(cls, config_type: str = 'video') -> Dict[str, Any]:
        """
        获取指定类型的配置
        
        Args:
            config_type: 配置类型，可选 'video', 'audio', 'text'
            
        Returns:
            配置字典
        """
        if config_type not in cls.DEFAULT_CONFIG:
            raise ValueError(f"不支持的配置类型: {config_type}")
        
        return cls.DEFAULT_CONFIG[config_type].copy()
    
    @classmethod
    def validate_config(cls) -> bool:
        """
        验证配置是否有效
        
        Returns:
            配置是否有效
        """
        if cls.ACCESS_KEY == 'your_access_key_here' or cls.SECRET_KEY == 'your_secret_key_here':
            logger.warning("七牛云AccessKey/SecretKey未配置，请设置环境变量QINIU_ACCESS_KEY和QINIU_SECRET_KEY")
            return False
        
        logger.info("七牛云配置验证通过")
        return True
    
    @classmethod
    def create_video_censor_params(cls, video_url: str, task_id: str) -> Dict[str, Any]:
        """
        创建视频审核参数
        
        Args:
            video_url: 视频URL
            task_id: 任务ID
            
        Returns:
            审核参数
        """
        config = cls.get_config('video')
        
        return {
            'data': {
                'uri': video_url
            },
            'params': {
                'scenes': config['scenes'],
                'async': config['async']
            },
            'op': 'video_censor'
        }
    
    @classmethod
    def create_audio_censor_params(cls, audio_url: str, task_id: str) -> Dict[str, Any]:
        """
        创建音频审核参数
        
        Args:
            audio_url: 音频URL
            task_id: 任务ID
            
        Returns:
            审核参数
        """
        config = cls.get_config('audio')
        
        return {
            'data': {
                'uri': audio_url
            },
            'params': {
                'scenes': config['scenes'],
                'async': config['async']
            },
            'op': 'audio_censor'
        }
    
    @classmethod
    def create_text_censor_params(cls, text: str, task_id: str) -> Dict[str, Any]:
        """
        创建文本审核参数
        
        Args:
            text: 待审核文本
            task_id: 任务ID
            
        Returns:
            审核参数
        """
        config = cls.get_config('text')
        
        return {
            'data': {
                'text': text
            },
            'params': {
                'scenes': config['scenes'],
                'async': config['async']
            },
            'op': 'text_censor'
        }


class QiniuClient:
    """七牛云客户端封装"""
    
    def __init__(self):
        """初始化七牛云客户端"""
        from qiniu import Auth
        
        self.access_key = QiniuConfig.ACCESS_KEY
        self.secret_key = QiniuConfig.SECRET_KEY
        self.auth = Auth(self.access_key, self.secret_key)
        
        if not QiniuConfig.validate_config():
            logger.warning("七牛云配置验证失败，某些功能可能无法正常工作")
    
    def get_token(self, body: Dict[str, Any]) -> str:
        """
        获取七牛云API调用token
        
        Args:
            body: 请求体
            
        Returns:
            API调用token
        """
        import json
        from qiniu import Auth
        
        # 使用七牛云SDK生成token
        return self.auth.token_with_data(json.dumps(body))
    
    def call_api(self, url: str, body: Dict[str, Any]) -> Dict[str, Any]:
        """
        调用七牛云API
        
        Args:
            url: API地址
            body: 请求体
            
        Returns:
            API响应结果
        """
        import requests
        import json
        import base64
        import hmac
        import hashlib
        from urllib.parse import urlparse
        
        # 解析URL
        parsed_url = urlparse(url)
        host = parsed_url.hostname
        
        # 请求数据
        request_data = json.dumps(body)
        
        # 构造签名字符串
        # 格式: <Method> <Path>\nHost: <Host>\nContent-Type: application/json\n\n<Body>
        path = parsed_url.path
        if parsed_url.query:
            path = f"{path}?{parsed_url.query}"
        
        sign_string = f"POST {path}\nHost: {host}\nContent-Type: application/json\n\n{request_data}"
        
        # 使用HMAC-SHA1生成签名
        sign = hmac.new(
            self.secret_key.encode('utf-8'),
            sign_string.encode('utf-8'),
            hashlib.sha1
        ).digest()
        
        # Base64编码签名
        encoded_sign = base64.urlsafe_b64encode(sign).decode('utf-8')
        
        # 生成Authorization头
        authorization = f"Qiniu {self.access_key}:{encoded_sign}"
        
        # 设置请求头
        headers = {
            'Content-Type': 'application/json',
            'Authorization': authorization,
            'Host': host
        }
        
        try:
            logger.info(f"调用七牛云API: {url}")
            response = requests.post(url, headers=headers, data=request_data, timeout=30)
            
            if response.status_code == 200:
                result = response.json()
                logger.info(f"七牛云API调用成功: {result.get('id', 'unknown')}")
                return result
            else:
                error_msg = f"七牛云API调用失败: {response.status_code} - {response.text}"
                logger.error(error_msg)
                raise requests.exceptions.HTTPError(error_msg)
            
        except requests.exceptions.RequestException as e:
            logger.error(f"七牛云API调用失败: {e}")
            raise
        except json.JSONDecodeError as e:
            logger.error(f"解析七牛云API响应失败: {e}")
            raise


def create_qiniu_client() -> Optional[QiniuClient]:
    """
    创建七牛云客户端
    
    Returns:
        七牛云客户端实例，如果配置无效则返回None
    """
    try:
        return QiniuClient()
    except Exception as e:
        logger.error(f"创建七牛云客户端失败: {e}")
        return None


# 全局客户端实例
_qiniu_client = None


def get_qiniu_client() -> Optional[QiniuClient]:
    """
    获取七牛云客户端（单例模式）
    
    Returns:
        七牛云客户端实例
    """
    global _qiniu_client
    
    if _qiniu_client is None:
        _qiniu_client = create_qiniu_client()
    
    return _qiniu_client