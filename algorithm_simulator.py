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
    
    def send_progress_message(self, task_id: str, module_type: str, progress: int, result_data: Dict[str, Any]):
        """
        发送进度消息到结果队列
        
        Args:
            task_id: 任务ID
            module_type: 模块类型（音频/视频/文本）
            progress: 进度百分比（0-100）
            result_data: 该模块的分析结果数据
        """
        message = {
            "taskId": task_id,
            "moduleType": module_type,
            "progress": progress,
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
            logger.info(f"[任务 {task_id}] 已发送进度消息: {module_type} - {progress}%")
        except Exception as e:
            logger.error(f"[任务 {task_id}] 发送进度消息失败: {e}")
            raise
    
    def process_task(self, task_message: Dict[str, Any]):
        """
        处理单个分析任务
        按照顺序执行：音频分析(33%) -> 视频分析(66%) -> 文本分析(100%)
        
        Args:
            task_message: 任务消息，包含 taskId, videoId 等信息
        """
        task_id = task_message.get("taskId")
        if not task_id:
            logger.error("任务消息缺少 taskId")
            return
        
        logger.info(f"[任务 {task_id}] 开始处理分析任务")
        
        try:
            # 第一阶段：音频分析 -> 33%
            audio_result = self.simulate_audio_analysis(task_id, task_message)
            self.send_progress_message(task_id, "音频", 33, audio_result)
            
            # 第二阶段：视频分析 -> 66%
            video_result = self.simulate_video_analysis(task_id, task_message)
            self.send_progress_message(task_id, "视频", 66, video_result)
            
            # 第三阶段：文本分析 -> 100%
            text_result = self.simulate_text_analysis(task_id, task_message)
            
            # 合并所有结果作为最终结果
            risk_score = round(random.uniform(0, 1), 4)
            risk_level = "LOW" if risk_score < 0.3 else ("MEDIUM" if risk_score < 0.7 else "HIGH")
            is_university_related = random.random() > 0.3
            university_name = random.choice([
                "清华大学", "北京大学", "复旦大学", "上海交通大学", "浙江大学",
                "南京大学", "中国科学技术大学", "哈尔滨工业大学", "西安交通大学", "武汉大学"
            ]) if is_university_related else None
            
            final_result = {
                "audioResult": audio_result,
                "videoResult": video_result,
                "textResult": text_result,
                # 综合评估结果
                "riskScore": risk_score,
                "riskLevel": risk_level,
                "isUniversityRelated": is_university_related,
                "universityName": university_name,
                "universityConfidence": round(random.uniform(0.7, 1.0), 4) if is_university_related else None,
                "spreadPotential": round(random.uniform(0, 1), 4)
            }
            
            # 发送最终结果（进度100%）
            self.send_progress_message(task_id, "文本", 100, final_result)
            
            logger.info(f"[任务 {task_id}] 分析任务全部完成")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] 处理任务失败: {e}")
            # 可以发送错误消息到结果队列
            error_message = {
                "taskId": task_id,
                "moduleType": "错误",
                "progress": -1,
                "resultData": {
                    "error": str(e)
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

