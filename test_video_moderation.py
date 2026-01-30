#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频审核 API 测试脚本

使用阿里云官方提供的公开测试视频 URL 调用视频审核接口，用于验证：
- 接口参数是否正确（url 传参）
- 任务提交与轮询结果是否正常

测试视频（阿里云 viapi 示例）：
https://viapi-test.oss-cn-shanghai.aliyuncs.com/viapi-3.0domepic/videoenhan/EnhanceVideoQuality/EnhanceVideoQuality1.mp4
"""

import os
import sys

# 确保能导入同目录下的客户端
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from aliyun_green_client import AliyunGreenClient

# 阿里云公开测试视频（MP4，可直接用）
TEST_VIDEO_URL = (
    "http://5aedd2d8.r12.cpolar.top/ican-videos/videos/2026/01/30/98bde6a56c0e4eaea808701021da24cb.mp4"
)

# 从环境变量读取密钥（推荐）；若无则使用占位，运行前请替换或设置环境变量
ACCESS_KEY_ID = "LTAI5tMnc6fAwoqpaSd3pybw"
ACCESS_KEY_SECRET = "GLYY1Jed0MqP86ZJKbkQoq5zEVMrcD"


def main():
    print("=" * 60)
    print("视频审核 API 测试（使用阿里云公开测试视频）")
    print("=" * 60)
    print(f"视频 URL: {TEST_VIDEO_URL}")
    print()

    if ACCESS_KEY_ID == "YOUR_ACCESS_KEY_ID" or ACCESS_KEY_SECRET == "YOUR_ACCESS_KEY_SECRET":
        print("[错误] 请设置环境变量 ALIYUN_ACCESS_KEY_ID 和 ALIYUN_ACCESS_KEY_SECRET")
        print("       或在脚本中替换 ACCESS_KEY_ID / ACCESS_KEY_SECRET 后重试。")
        return 1

    print("初始化客户端（green-cip 上海）...")
    client = AliyunGreenClient(ACCESS_KEY_ID, ACCESS_KEY_SECRET, region="cn-shanghai")

    print("\n" + "=" * 60)
    print("提交视频审核任务并轮询结果（同步模式）...")
    print("=" * 60)

    result = client.video_scan_sync(
        video_url=TEST_VIDEO_URL,
        data_id="test_viapi_video_001",
        poll_interval=10,
        max_wait=300,
    )

    print("\n" + "=" * 60)
    print("最终审核结果")
    print("=" * 60)
    print(f"  passed:     {result.get('passed')}")
    print(f"  label:      {result.get('label')}")
    print(f"  suggestion: {result.get('suggestion')}")
    print(f"  confidence: {result.get('confidence')}")
    print(f"  violations: {result.get('violations')}")
    print()

    if result.get("passed"):
        print("[SUCCESS] 视频审核通过（无违规）。")
    elif result.get("label") == "error" or result.get("violations"):
        print("[INFO] 未通过或存在异常，请根据 violations 排查。")
    else:
        print("[INFO] 审核结束，请根据上述字段处理。")

    print("\n测试结束。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
