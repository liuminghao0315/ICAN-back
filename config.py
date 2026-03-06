# -*- coding: utf-8 -*-
"""
Python 后端配置文件
替代 .env 文件，所有配置集中管理
"""

import os

class Config:
    """
    应用配置类
    """

    # =============================================
    # DeepSeek API 配置
    # =============================================
    # DeepSeek API Key - 用于生成分析证据
    # 获取方式：访问 https://platform.deepseek.com/ 注册并获取 API Key
    DEEPSEEK_API_KEY = "sk-510f09778e48403283036126240de79d"

    # DeepSeek API 地址（一般不需要修改）
    DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"

    # DeepSeek 模型名称（一般不需要修改）
    DEEPSEEK_MODEL = "deepseek-chat"

    # DeepSeek API 超时时间（秒）
    DEEPSEEK_TIMEOUT = 45

    # 转写文本最小长度（低于此长度跳过 API 调用）
    DEEPSEEK_MIN_TRANSCRIPTION_LENGTH = 20

    # =============================================
    # 其他配置（如需要可以添加）
    # =============================================

    @classmethod
    def get(cls, key: str, default=None):
        """
        获取配置项
        优先从环境变量读取，如果环境变量不存在则使用类属性
        """
        return os.getenv(key, getattr(cls, key, default))

    @classmethod
    def is_configured(cls) -> bool:
        """
        检查 DeepSeek API Key 是否已配置
        """
        key = cls.get("DEEPSEEK_API_KEY", "")
        return bool(key and key.strip() and key != "填写你的 DeepSeek API Key")


# 导出配置实例
config = Config()

# 兼容旧代码：可以直接从环境变量读取（如果设置了的话）
def get_deepseek_api_key() -> str:
    """获取 DeepSeek API Key"""
    return config.get("DEEPSEEK_API_KEY", "")

def get_deepseek_api_url() -> str:
    """获取 DeepSeek API URL"""
    return config.get("DEEPSEEK_API_URL", "https://api.deepseek.com/v1/chat/completions")

def get_deepseek_model() -> str:
    """获取 DeepSeek 模型名称"""
    return config.get("DEEPSEEK_MODEL", "deepseek-chat")

def get_deepseek_timeout() -> int:
    """获取 DeepSeek API 超时时间"""
    return int(config.get("DEEPSEEK_TIMEOUT", 45))

def get_min_transcription_length() -> int:
    """获取最小转写文本长度"""
    return int(config.get("DEEPSEEK_MIN_TRANSCRIPTION_LENGTH", 20))
