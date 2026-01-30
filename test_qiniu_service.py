#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
快速测试七牛云服务是否开通 - 使用正确的签名方式
"""

import os
import sys
import json
import requests
import base64
import hmac
import hashlib
from urllib.parse import urlparse

# 加载环境变量
def load_env():
    """加载.env文件"""
    if os.path.exists('.env'):
        with open('.env', 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and '=' in line and not line.startswith('#'):
                    key, value = line.split('=', 1)
                    key = key.strip()
                    value = value.strip()
                    if (value.startswith('"') and value.endswith('"')) or \
                       (value.startswith("'") and value.endswith("'")):
                        value = value[1:-1]
                    os.environ[key] = value

load_env()

# 获取密钥
access_key = os.environ.get('QINIU_ACCESS_KEY')
secret_key = os.environ.get('QINIU_SECRET_KEY')

if not access_key or not secret_key:
    print("❌ 未找到七牛云密钥配置")
    sys.exit(1)

print("=" * 60)
print("七牛云服务状态测试 - 使用正确签名方式")
print("=" * 60)
print()

def generate_qiniu_signature(url, method="POST", body=None):
    """
    生成七牛云API签名
    根据七牛云官方鉴权文档
    """
    # 解析URL
    parsed_url = urlparse(url)
    host = parsed_url.hostname
    
    # 请求数据
    if body is None:
        body_data = ""
    else:
        body_data = json.dumps(body) if isinstance(body, dict) else body
    
    # 构造签名字符串
    # 格式: <Method> <Path>\nHost: <Host>\nContent-Type: application/json\n\n<Body>
    path = parsed_url.path
    if parsed_url.query:
        path = f"{path}?{parsed_url.query}"
    
    sign_string = f"{method} {path}\nHost: {host}\nContent-Type: application/json\n\n{body_data}"
    
    # 使用HMAC-SHA1生成签名
    sign = hmac.new(
        secret_key.encode('utf-8'),
        sign_string.encode('utf-8'),
        hashlib.sha1
    ).digest()
    
    # Base64编码
    encoded_sign = base64.urlsafe_b64encode(sign).decode('utf-8')
    
    # 生成Authorization头
    authorization = f"Qiniu {access_key}:{encoded_sign}"
    
    return authorization

# 测试文本审核API
try:
    # 测试请求体
    test_body = {
        "data": {
            "text": "这是一个简单的测试文本，用于验证服务是否开通。"
        },
        "params": {
            "scenes": ["antispam"]
        }
    }
    
    # 生成签名
    url = 'http://ai.qiniuapi.com/v3/text/censor'
    authorization = generate_qiniu_signature(url, "POST", test_body)
    
    # 设置请求头
    headers = {
        "Content-Type": "application/json",
        "Authorization": authorization,
        "Host": "ai.qiniuapi.com"
    }
    
    print(f"📡 调用API: {url}")
    print(f"🔑 AccessKey: {access_key[:10]}...")
    print(f"🔐 SecretKey: {secret_key[:10]}...")
    print()
    
    response = requests.post(url, headers=headers, data=json.dumps(test_body), timeout=10)
    
    if response.status_code == 200:
        result = response.json()
        print("✅ API调用成功！")
        print(f"📊 响应结果: {json.dumps(result, ensure_ascii=False, indent=2)}")
        print()
        print("🎉 恭喜！七牛云API调用成功。")
        print("🚀 现在可以重新启动算法模拟器：")
        print("   start_qiniu_algorithm.bat")
    else:
        print(f"❌ API调用失败: {response.status_code}")
        print(f"📋 错误信息: {response.text}")
        print()
        print("💡 可能的原因：")
        print("   1. 密钥不正确")
        print("   2. 签名算法错误")
        print("   3. 网络连接问题")
        
except Exception as e:
    print(f"❌ 测试过程中发生异常: {e}")
    print()
    print("🔧 可能的原因：")
    print("   - 网络连接问题")
    print("   - 密钥格式错误")
    print("   - 七牛云服务异常")

print()
print("=" * 60)
