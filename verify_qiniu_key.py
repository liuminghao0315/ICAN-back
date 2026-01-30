#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证七牛云密钥是否有效
"""

import os
import logging
import json
import requests
from qiniu import Auth

# 配置日志
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


def load_env_file(env_file: str = '.env') -> bool:
    """从 .env 文件加载环境变量"""
    try:
        if not os.path.exists(env_file):
            logger.warning(f"环境变量文件不存在: {env_file}")
            return False
        
        with open(env_file, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                
                if '=' in line:
                    key, value = line.split('=', 1)
                    key = key.strip()
                    value = value.strip()
                    
                    # 移除引号
                    if (value.startswith('"') and value.endswith('"')) or \
                       (value.startswith("'") and value.endswith("'")):
                        value = value[1:-1]
                    
                    if key and value and key not in os.environ:
                        os.environ[key] = value
        
        return True
        
    except Exception as e:
        logger.error(f"加载 .env 文件失败: {e}")
        return False


def verify_qiniu_key(access_key: str, secret_key: str) -> bool:
    """
    验证七牛云密钥是否有效
    
    Args:
        access_key: 七牛云AccessKey
        secret_key: 七牛云SecretKey
        
    Returns:
        密钥是否有效
    """
    try:
        # 创建Auth对象
        auth = Auth(access_key, secret_key)
        
        # 创建一个简单的请求体
        test_body = {
            'data': {
                'text': '这是一个测试文本'
            },
            'params': {
                'scenes': ['antispam'],
                'async': False
            },
            'op': 'text_censor'
        }
        
        # 生成token
        token = auth.token_with_data(json.dumps(test_body))
        
        logger.info(f"✅ 密钥验证通过")
        logger.info(f"AccessKey: {access_key[:10]}...")
        logger.info(f"SecretKey: {secret_key[:10]}...")
        logger.info(f"Token: {token[:50]}...")
        
        return True
        
    except Exception as e:
        logger.error(f"❌ 密钥验证失败: {e}")
        return False


def test_api_call(access_key: str, secret_key: str) -> bool:
    """
    测试API调用
    
    Args:
        access_key: 七牛云AccessKey
        secret_key: 七牛云SecretKey
        
    Returns:
        API调用是否成功
    """
    try:
        # 创建Auth对象
        auth = Auth(access_key, secret_key)
        
        # 测试请求体
        test_body = {
            'data': {
                'text': '这是一个测试文本，用于验证七牛云API'
            },
            'params': {
                'scenes': ['antispam'],
                'async': False
            },
            'op': 'text_censor'
        }
        
        # 生成token
        token = auth.token_with_data(json.dumps(test_body))
        
        # 设置请求头
        headers = {
            'Content-Type': 'application/json',
            'Authorization': f'Qiniu {token}'
        }
        
        # 调用API
        url = 'http://ai.qiniuapi.com/v3/text/censor'
        logger.info(f"调用七牛云API: {url}")
        
        response = requests.post(url, headers=headers, json=test_body, timeout=10)
        
        if response.status_code == 200:
            result = response.json()
            logger.info(f"✅ API调用成功: {result}")
            return True
        else:
            logger.error(f"❌ API调用失败: {response.status_code} - {response.text}")
            return False
            
    except Exception as e:
        logger.error(f"❌ API调用异常: {e}")
        return False


def main():
    """主函数"""
    print("=" * 60)
    print("七牛云密钥验证工具")
    print("=" * 60)
    print()
    
    # 加载.env文件
    if not load_env_file():
        print("❌ 无法加载 .env 文件")
        return
    
    # 获取密钥
    access_key = os.environ.get('QINIU_ACCESS_KEY')
    secret_key = os.environ.get('QINIU_SECRET_KEY')
    
    if not access_key or not secret_key:
        print("❌ 未找到七牛云密钥配置")
        print("请在 .env 文件中配置 QINIU_ACCESS_KEY 和 QINIU_SECRET_KEY")
        return
    
    print(f"📋 找到的密钥配置:")
    print(f"   AccessKey: {access_key[:10]}...")
    print(f"   SecretKey: {secret_key[:10]}...")
    print()
    
    # 验证密钥格式
    print("1. 验证密钥格式...")
    if verify_qiniu_key(access_key, secret_key):
        print("   ✅ 密钥格式验证通过")
    else:
        print("   ❌ 密钥格式验证失败")
        return
    
    print()
    
    # 测试API调用
    print("2. 测试API调用...")
    if test_api_call(access_key, secret_key):
        print("   ✅ API调用成功")
    else:
        print("   ❌ API调用失败")
        print()
        print("⚠️ 可能的原因:")
        print("   - 密钥没有内容审核服务的权限")
        print("   - 七牛云账户未开通内容审核服务")
        print("   - 账户余额不足")
        print("   - 网络连接问题")
        print()
        print("💡 解决方案:")
        print("   1. 登录七牛云控制台: https://portal.qiniu.com/")
        print("   2. 确认已开通内容审核服务")
        print("   3. 检查账户余额")
        print("   4. 确认密钥有正确的权限")
    
    print()
    print("=" * 60)


if __name__ == "__main__":
    main()