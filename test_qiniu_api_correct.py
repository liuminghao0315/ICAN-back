#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试七牛云API的正确调用方式
根据七牛云官方文档，内容审核API调用方式
"""

import os
import sys
import json
import requests
import base64
import hmac
import hashlib
from datetime import datetime
from urllib.parse import urlparse

# 从.env文件加载配置
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
ACCESS_KEY = os.environ.get('QINIU_ACCESS_KEY')
SECRET_KEY = os.environ.get('QINIU_SECRET_KEY')

if not ACCESS_KEY or not SECRET_KEY:
    print("❌ 未找到七牛云密钥配置")
    sys.exit(1)

print("=" * 60)
print("七牛云API调用测试 - 正确方式")
print("=" * 60)
print()

def generate_qiniu_signature(url, method="POST", body=None):
    """
    生成七牛云API签名
    根据七牛云官方鉴权文档
    """
    # 解析URL
    parsed_url = urlparse(url)
    host = parsed_host = parsed_url.hostname
    
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
    
    # 构造签名字符串
    sign_string = f"{method} {path}\nHost: {host}\nContent-Type: application/json\n\n{body_data}"
    
    print(f"📝 签名字符串: {sign_string}")
    print()
    
    # 使用HMAC-SHA1生成签名
    sign = hmac.new(
        SECRET_KEY.encode('utf-8'),
        sign_string.encode('utf-8'),
        hashlib.sha1
    ).digest()
    
    # Base64编码
    encoded_sign = base64.urlsafe_b64encode(sign).decode('utf-8')
    
    # 生成Authorization头
    authorization = f"Qiniu {ACCESS_KEY}:{encoded_sign}"
    
    return authorization, sign_string

def test_text_censor():
    """测试文本审核API"""
    print("📋 测试文本审核API")
    print("-" * 40)
    
    url = "http://ai.qiniuapi.com/v3/text/censor"
    
    # 构建请求体
    body = {
        "data": {
            "text": "这是一个测试文本，用于验证API调用"
        },
        "params": {
            "scenes": ["antispam"]
        }
    }
    
    # 生成签名
    authorization, sign_string = generate_qiniu_signature(url, "POST", body)
    
    # 设置请求头
    headers = {
        "Content-Type": "application/json",
        "Authorization": authorization,
        "Host": "ai.qiniuapi.com"
    }
    
    print(f"🔗 请求URL: {url}")
    print(f"🔑 Authorization: {authorization[:50]}...")
    print()
    
    try:
        # 发送请求
        response = requests.post(
            url,
            headers=headers,
            data=json.dumps(body),
            timeout=10
        )
        
        print(f"📊 响应状态码: {response.status_code}")
        print(f"📋 响应内容: {response.text[:200]}...")
        print()
        
        if response.status_code == 200:
            result = response.json()
            print("✅ API调用成功！")
            print(f"📈 结果: {json.dumps(result, ensure_ascii=False, indent=2)}")
            return True
        else:
            print(f"❌ API调用失败: {response.status_code}")
            print(f"🔍 详细错误: {response.text}")
            return False
            
    except Exception as e:
        print(f"❌ 请求异常: {e}")
        return False

def test_video_censor():
    """测试视频审核API"""
    print()
    print("📋 测试视频审核API")
    print("-" * 40)
    
    url = "http://ai.qiniuapi.com/v3/video/censor"
    
    # 构建请求体
    body = {
        "data": {
            "uri": "http://example.com/test.mp4"
        },
        "params": {
            "scenes": ["pulp", "terror", "politician", "ads"]
        }
    }
    
    # 生成签名
    authorization, sign_string = generate_qiniu_signature(url, "POST", body)
    
    # 设置请求头
    headers = {
        "Content-Type": "application/json",
        "Authorization": authorization,
        "Host": "ai.qiniuapi.com"
    }
    
    print(f"🔗 请求URL: {url}")
    print(f"🔑 Authorization: {authorization[:50]}...")
    print()
    
    try:
        # 发送请求
        response = requests.post(
            url,
            headers=headers,
            data=json.dumps(body),
            timeout=10
        )
        
        print(f"📊 响应状态码: {response.status_code}")
        print(f"📋 响应内容: {response.text[:200]}...")
        print()
        
        if response.status_code == 200:
            result = response.json()
            print("✅ API调用成功！")
            print(f"📈 结果: {json.dumps(result, ensure_ascii=False, indent=2)}")
            return True
        else:
            print(f"❌ API调用失败: {response.status_code}")
            print(f"🔍 详细错误: {response.text}")
            return False
            
    except Exception as e:
        print(f"❌ 请求异常: {e}")
        return False

def main():
    """主测试函数"""
    print(f"🔑 AccessKey: {ACCESS_KEY[:10]}...")
    print(f"🔐 SecretKey: {SECRET_KEY[:10]}...")
    print()
    
    # 测试文本审核
    if test_text_censor():
        print("✅ 文本审核API调用成功！")
    else:
        print("❌ 文本审核API调用失败")
    
    print()
    print("=" * 60)
    
    print()
    print("💡 如果API调用失败，可能的原因:")
    print("   1. 密钥格式错误")
    print("   2. 签名算法不正确")
    print("   3. 网络连接问题")
    print("   4. 七牛云服务异常")
    
    print()
    print("🛠️ 建议:")
    print("   1. 检查密钥是否正确")
    print("   2. 查看七牛云官方文档")
    print("   3. 联系七牛云技术支持")

if __name__ == "__main__":
    main()