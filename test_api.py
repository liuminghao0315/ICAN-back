#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试阿里云内容安全API（增强版）
"""

from aliyun_green_client import AliyunGreenClient

ACCESS_KEY_ID = "LTAI5tMnc6fAwoqpaSd3pybw"
ACCESS_KEY_SECRET = "GLYY1Jed0MqP86ZJKbkQoq5zEVMrcD"

print("="*60)
print("初始化客户端...")
print("="*60)

client = AliyunGreenClient(ACCESS_KEY_ID, ACCESS_KEY_SECRET)

print("\n" + "="*60)
print("测试文本检测（增强版API）...")
print("="*60)

result = client.text_scan("这是一段正常的校园生活分享文本")
print(f"\n最终结果: {result}")

if result.get("passed"):
    print("\n[SUCCESS] 文本检测通过!")
else:
    print(f"\n[INFO] 检测结果: {result}")

print("\n" + "="*60)
print("测试完成!")
print("="*60)
