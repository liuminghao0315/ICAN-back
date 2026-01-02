#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频分析算法模拟脚本
模拟音频分析、视频分析、文本分析三个模块的处理过程
"""

import json
import time
import random
import pika
import logging
import threading
from typing import Dict, Any

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# RabbitMQ 配置
RABBITMQ_HOST = '192.168.6.130'
RABBITMQ_PORT = 5672
RABBITMQ_USERNAME = 'guest'
RABBITMQ_PASSWORD = 'guest'
RABBITMQ_VIRTUAL_HOST = '/'

# 队列名称
TASK_QUEUE = 'algorithm.task.queue'  # 从 Java 接收任务
RESULT_QUEUE = 'algorithm.result.queue'  # 向 Java 发送结果


class AlgorithmSimulator:
    """算法模拟器"""
    
    def __init__(self):
        """初始化 RabbitMQ 连接"""
        self.connection = None
        self.channel = None
        self.connect()
    
    def connect(self):
        """连接到 RabbitMQ"""
        try:
            credentials = pika.PlainCredentials(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
            parameters = pika.ConnectionParameters(
                host=RABBITMQ_HOST,
                port=RABBITMQ_PORT,
                virtual_host=RABBITMQ_VIRTUAL_HOST,
                credentials=credentials
            )
            self.connection = pika.BlockingConnection(parameters)
            self.channel = self.connection.channel()
            
            # 声明队列
            self.channel.queue_declare(queue=TASK_QUEUE, durable=True)
            self.channel.queue_declare(queue=RESULT_QUEUE, durable=True)
            
            logger.info("成功连接到 RabbitMQ")
        except Exception as e:
            logger.error(f"连接 RabbitMQ 失败: {e}")
            raise
    
    def simulate_audio_analysis(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        模拟音频分析模块
        返回音频分析结果
        """
        logger.info(f"[任务 {task_id}] 开始音频分析...")
        
        # 模拟处理时间（2-5秒）
        process_time = random.uniform(2, 5)
        time.sleep(process_time)
        
        # 生成模拟音频分析结果
        result = {
            "hasAudio": True,
            "audioQuality": round(random.uniform(0.6, 1.0), 4),
            "speechRatio": round(random.uniform(0.3, 0.8), 4),
            "musicRatio": round(random.uniform(0, 0.3), 4),
            "noiseLevel": round(random.uniform(0, 0.3), 4),
            "volumeLevel": round(random.uniform(0.4, 0.8), 4),
            "emotionInVoice": random.choice(["calm", "energetic", "neutral"]),
            "transcription": "大家好，今天我来分享一下在大学的学习经验。希望我的分享对你们有帮助。"
        }
        
        logger.info(f"[任务 {task_id}] 音频分析完成")
        return result
    
    def simulate_video_analysis(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        模拟视频分析模块
        返回视频分析结果
        """
        logger.info(f"[任务 {task_id}] 开始视频分析...")
        
        # 模拟处理时间（3-6秒）
        process_time = random.uniform(3, 6)
        time.sleep(process_time)
        
        # 生成模拟视频分析结果
        result = {
            "duration": video_info.get("videoDuration", 60.0),
            "width": 1920,
            "height": 1080,
            "fps": random.randint(24, 60),
            "sceneType": random.choice(["教室", "图书馆", "操场", "宿舍", "食堂", "实验室", "报告厅", "校园户外"]),
            "sceneConfidence": round(random.uniform(0.7, 1.0), 4),
            "faceCount": random.randint(0, 10),
            "hasPerson": random.choice([True, False]),
            "qualityScore": round(random.uniform(0.5, 1.0), 4),
            "brightness": round(random.uniform(0.3, 0.7), 4),
            "clarity": round(random.uniform(0.6, 1.0), 4)
        }
        
        logger.info(f"[任务 {task_id}] 视频分析完成")
        return result
    
    def simulate_text_analysis(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        模拟文本分析模块
        返回文本分析结果
        """
        logger.info(f"[任务 {task_id}] 开始文本分析...")
        
        # 模拟处理时间（2-4秒）
        process_time = random.uniform(2, 4)
        time.sleep(process_time)
        
        # 生成模拟文本分析结果
        keywords = random.sample([
            "大学生", "校园", "学习", "考试", "社团", "青春", "梦想", "奋斗",
            "创新", "创业", "实习", "就业", "考研", "保研", "留学", "志愿者"
        ], random.randint(3, 7))
        
        result = {
            "titleLength": len(video_info.get("videoTitle", "")),
            "hasDescription": bool(video_info.get("videoTitle")),
            "titleSentiment": round(random.uniform(-1, 1), 4),
            "containsKeywords": random.choice([True, False]),
            "languageConfidence": round(random.uniform(0.9, 1.0), 4),
            "keywords": keywords,
            "topicCategory": random.choice([
                "校园生活", "学术讨论", "社团活动", "体育运动", "艺术表演",
                "科技创新", "创业分享", "心理健康", "就业指导", "社会实践"
            ]),
            "sentimentScore": round(random.uniform(-1, 1), 4),
            "sentimentLabel": random.choice(["POSITIVE", "NEGATIVE", "NEUTRAL"])
        }
        
        logger.info(f"[任务 {task_id}] 文本分析完成")
        return result
    
    def send_progress_message(self, task_id: str, module_type: str, result_data: Dict[str, Any]):
        """
        发送模块完成消息到结果队列（不包含进度百分比，由Java动态计算）
        
        Args:
            task_id: 任务ID
            module_type: 模块类型（audio/video/text）
            result_data: 该模块的分析结果数据
        """
        message = {
            "taskId": task_id,
            "moduleType": module_type,
            "resultData": result_data
        }
        
        try:
            self.channel.basic_publish(
                exchange='',
                routing_key=RESULT_QUEUE,
                body=json.dumps(message, ensure_ascii=False),
                properties=pika.BasicProperties(
                    delivery_mode=2,  # 消息持久化
                )
            )
            logger.info(f"[任务 {task_id}] 已发送模块完成消息: {module_type}")
        except Exception as e:
            logger.error(f"[任务 {task_id}] 发送消息失败: {e}")
            raise
    
    def process_module(self, task_id: str, task_message: Dict[str, Any], module_type: str, 
                      simulate_func, module_name: str):
        """
        处理单个分析模块（在独立线程中执行）
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
            module_type: 模块类型（audio/video/text）
            simulate_func: 模拟分析函数
            module_name: 模块名称（用于日志）
        """
        try:
            logger.info(f"[任务 {task_id}] 开始{module_name}分析...")
            
            # 执行模拟分析
            result = simulate_func(task_id, task_message)
            
            # 随机延时，模拟不同模块在不同时间完成
            delay = random.uniform(2, 8)  # 2-8秒随机延时
            time.sleep(delay)
            
            # 发送模块完成消息（不包含进度，由Java动态计算）
            self.send_progress_message(task_id, module_type, result)
            
            logger.info(f"[任务 {task_id}] {module_name}分析完成")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] {module_name}分析失败: {e}")
            # 发送错误消息
            error_message = {
                "taskId": task_id,
                "moduleType": "error",
                "resultData": {
                    "error": str(e),
                    "failedModule": module_type
                }
            }
            try:
                self.channel.basic_publish(
                    exchange='',
                    routing_key=RESULT_QUEUE,
                    body=json.dumps(error_message, ensure_ascii=False),
                    properties=pika.BasicProperties(
                        delivery_mode=2,
                    )
                )
            except:
                pass
    
    def process_task(self, task_message: Dict[str, Any]):
        """
        处理单个分析任务（并发执行三个模块）
        使用多线程同时启动音频、视频、文本分析，它们会在不同时间点完成
        
        Args:
            task_message: 任务消息，包含 taskId, videoId 等信息
        """
        task_id = task_message.get("taskId")
        if not task_id:
            logger.error("任务消息缺少 taskId")
            return
        
        logger.info(f"[任务 {task_id}] 开始并发处理分析任务（音频、视频、文本同时启动）")
        
        try:
            # 创建三个线程，并发执行三个分析模块
            threads = []
            
            # 音频分析线程
            audio_thread = threading.Thread(
                target=self.process_module,
                args=(task_id, task_message, "audio", self.simulate_audio_analysis, "音频"),
                name=f"Audio-{task_id}"
            )
            threads.append(audio_thread)
            
            # 视频分析线程
            video_thread = threading.Thread(
                target=self.process_module,
                args=(task_id, task_message, "video", self.simulate_video_analysis, "视频"),
                name=f"Video-{task_id}"
            )
            threads.append(video_thread)
            
            # 文本分析线程
            text_thread = threading.Thread(
                target=self.process_module,
                args=(task_id, task_message, "text", self.simulate_text_analysis, "文本"),
                name=f"Text-{task_id}"
            )
            threads.append(text_thread)
            
            # 启动所有线程
            for thread in threads:
                thread.start()
                logger.debug(f"[任务 {task_id}] 启动线程: {thread.name}")
            
            # 等待所有线程完成
            for thread in threads:
                thread.join()
            
            logger.info(f"[任务 {task_id}] Python端所有模块分析完成，等待Java端整合分析")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] 处理任务失败: {e}")
    
    def on_task_received(self, ch, method, properties, body):
        """
        处理接收到的任务消息
        """
        try:
            message_str = body.decode('utf-8')
            task_message = json.loads(message_str)
            logger.info(f"收到新任务: {task_message.get('taskId')}")
            
            # 处理任务
            self.process_task(task_message)
            
            # 确认消息已处理
            ch.basic_ack(delivery_tag=method.delivery_tag)
            
        except Exception as e:
            logger.error(f"处理任务消息失败: {e}")
            # 拒绝消息并重新入队（可选）
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
    
    def start_consuming(self):
        """开始监听任务队列"""
        logger.info("开始监听任务队列...")
        
        # 设置每次只接收一条消息
        self.channel.basic_qos(prefetch_count=1)
        
        # 开始消费消息
        self.channel.basic_consume(
            queue=TASK_QUEUE,
            on_message_callback=self.on_task_received
        )
        
        logger.info("等待任务消息...")
        try:
            self.channel.start_consuming()
        except KeyboardInterrupt:
            logger.info("收到中断信号，停止监听")
            self.channel.stop_consuming()
            self.connection.close()
    
    def close(self):
        """关闭连接"""
        if self.connection and not self.connection.is_closed:
            self.connection.close()
            logger.info("RabbitMQ 连接已关闭")


def main():
    """主函数"""
    simulator = AlgorithmSimulator()
    try:
        simulator.start_consuming()
    except Exception as e:
        logger.error(f"运行错误: {e}")
    finally:
        simulator.close()


if __name__ == '__main__':
    main()

