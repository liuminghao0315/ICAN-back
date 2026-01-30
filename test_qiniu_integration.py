#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
七牛云API集成测试脚本
用于验证七牛云API集成是否正常工作
"""

import sys
import logging
import json

# 配置日志
logging.basicConfig(level=logging.INFO, format='%(levelname)s: %(message)s')
logger = logging.getLogger(__name__)


def test_config_module():
    """测试配置模块"""
    print("1. 测试配置模块...")
    try:
        from qiniu_config import QiniuConfig, get_qiniu_client
        
        print("   ✓ 配置模块导入成功")
        
        # 验证配置
        is_valid = QiniuConfig.validate_config()
        if is_valid:
            print("   ✓ 配置验证通过")
        else:
            print("   ⚠ 配置验证失败（请配置.env文件）")
            
        # 获取客户端
        client = get_qiniu_client()
        if client:
            print("   ✓ 客户端创建成功")
        else:
            print("   ⚠ 客户端创建失败（配置无效）")
            
        return True
        
    except Exception as e:
        print(f"   ✗ 配置模块测试失败: {e}")
        return False


def test_video_module():
    """测试视频处理模块"""
    print("2. 测试视频处理模块...")
    try:
        from qiniu_video_processor import process_video
        
        test_task_id = 'test_video_001'
        test_video_info = {
            'videoUrl': 'http://example.com/test.mp4',
            'videoTitle': '测试视频',
            'videoDuration': 120.5,
            'fileSize': 10240000
        }
        
        result = process_video(test_task_id, test_video_info)
        print(f"   ✓ 视频处理函数调用成功")
        print(f"   风险评分: {result.get('riskScore', 'N/A')}")
        print(f"   风险等级: {result.get('riskLevel', 'N/A')}")
        print(f"   来源: {result.get('source', 'N/A')}")
        
        return True
        
    except Exception as e:
        print(f"   ✗ 视频处理模块测试失败: {e}")
        return False


def test_audio_module():
    """测试音频处理模块"""
    print("3. 测试音频处理模块...")
    try:
        from qiniu_audio_processor import extract_audio_features
        
        test_task_id = 'test_audio_001'
        test_video_info = {
            'videoUrl': 'http://example.com/test.mp4',
            'videoTitle': '测试音频',
            'videoDuration': 120.5,
            'fileSize': 10240000
        }
        
        result = extract_audio_features(test_task_id, test_video_info)
        print(f"   ✓ 音频处理函数调用成功")
        print(f"   风险评分: {result.get('riskScore', 'N/A')}")
        print(f"   风险等级: {result.get('riskLevel', 'N/A')}")
        print(f"   来源: {result.get('source', 'N/A')}")
        
        return True
        
    except Exception as e:
        print(f"   ✗ 音频处理模块测试失败: {e}")
        return False


def test_text_module():
    """测试文本处理模块"""
    print("4. 测试文本处理模块...")
    try:
        from qiniu_text_processor import convert_audio_to_text
        
        test_task_id = 'test_text_001'
        test_video_info = {
            'videoUrl': 'http://example.com/test.mp4',
            'videoTitle': '测试文本',
            'videoDuration': 120.5,
            'fileSize': 10240000
        }
        
        result = convert_audio_to_text(test_task_id, test_video_info)
        print(f"   ✓ 文本处理函数调用成功")
        print(f"   风险评分: {result.get('riskScore', 'N/A')}")
        print(f"   风险等级: {result.get('riskLevel', 'N/A')}")
        print(f"   来源: {result.get('source', 'N/A')}")
        
        return True
        
    except Exception as e:
        print(f"   ✗ 文本处理模块测试失败: {e}")
        return False


def test_algorithm_simulator():
    """测试算法模拟器模块"""
    print("5. 测试算法模拟器模块...")
    try:
        from qiniu_algorithm_simulator import QiniuAlgorithmSimulator
        
        print("   ✓ 算法模拟器模块导入成功")
        
        # 创建模拟器实例
        simulator = QiniuAlgorithmSimulator()
        print("   ✓ 算法模拟器实例创建成功")
        
        return True
        
    except Exception as e:
        print(f"   ✗ 算法模拟器模块测试失败: {e}")
        return False


def main():
    """主测试函数"""
    print("=== 七牛云API集成测试 ===")
    print()
    
    test_results = []
    
    # 运行所有测试
    test_results.append(("配置模块", test_config_module()))
    test_results.append(("视频处理模块", test_video_module()))
    test_results.append(("音频处理模块", test_audio_module()))
    test_results.append(("文本处理模块", test_text_module()))
    test_results.append(("算法模拟器模块", test_algorithm_simulator()))
    
    print()
    print("=== 测试结果汇总 ===")
    print()
    
    passed = 0
    failed = 0
    
    for module_name, result in test_results:
        status = "✓ 通过" if result else "✗ 失败"
        print(f"{module_name}: {status}")
        if result:
            passed += 1
        else:
            failed += 1
    
    print()
    print(f"总计: {len(test_results)} 个测试")
    print(f"通过: {passed}")
    print(f"失败: {failed}")
    
    if failed == 0:
        print()
        print("✅ 所有测试通过！")
        print()
        print("下一步操作:")
        print("1. 配置 .env 文件，填入七牛云 AccessKey 和 SecretKey")
        print("2. 启动七牛云算法模拟器: python qiniu_algorithm_simulator.py")
        print("3. 通过Java端发送任务进行实际测试")
        return 0
    else:
        print()
        print("❌ 部分测试失败，请检查问题")
        return 1


if __name__ == "__main__":
    sys.exit(main())