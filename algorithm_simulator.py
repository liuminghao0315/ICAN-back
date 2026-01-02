#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频分析算法主模块
实现双向异步事件驱动架构：
1. 监听任务队列（Java -> Python）
2. 模拟媒体分割过程
3. 并发调用视频处理模块和音频处理模块
4. 异步发送结果到结果队列（Python -> Java）
"""

import json
import time
import random
import pika
import logging
import threading
from typing import Dict, Any

# 导入子模块
import 视频处理
import 音频处理

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
TASK_DISPATCH_QUEUE = 'algorithm.task.queue'  # 从 Java 接收任务
RESULT_CALLBACK_QUEUE = 'algorithm.result.queue'  # 向 Java 发送结果


class AlgorithmSimulator:
    """算法模拟器 - 实现双向异步事件驱动架构"""
    
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
            self.channel.queue_declare(queue=TASK_DISPATCH_QUEUE, durable=True)
            self.channel.queue_declare(queue=RESULT_CALLBACK_QUEUE, durable=True)
            
            logger.info("成功连接到 RabbitMQ")
        except Exception as e:
            logger.error(f"连接 RabbitMQ 失败: {e}")
            raise
    
    def simulate_media_splitting(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        模拟媒体分割过程
        将视频文件分割为视觉流和音频流
        
        Args:
            task_id: 任务ID
            video_info: 视频信息
            
        Returns:
            分割结果信息
        """
        logger.info(f"[任务 {task_id}] 开始媒体分割...")
        
        # 模拟分割处理时间（1-3秒）
        process_time = random.uniform(1, 3)
        time.sleep(process_time)
        
        result = {
            "visualStreamReady": True,
            "audioStreamReady": True,
            "splitDuration": round(process_time, 2),
            "videoFilePath": video_info.get("videoUrl", ""),
            "fileSize": video_info.get("fileSize", 0)
        }
        
        logger.info(f"[任务 {task_id}] 媒体分割完成，视觉流和音频流已就绪")
        return result
    
    
    def send_result_message(self, task_id: str, module_type: str, result_data: Dict[str, Any]):
        """
        发送模块完成消息到结果回调队列
        
        Args:
            task_id: 任务ID
            module_type: 模块类型（video/audio/text）
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
                routing_key=RESULT_CALLBACK_QUEUE,
                body=json.dumps(message, ensure_ascii=False),
                properties=pika.BasicProperties(
                    delivery_mode=2,  # 消息持久化
                )
            )
            logger.info(f"[任务 {task_id}] 已发送模块结果消息: {module_type}")
        except Exception as e:
            logger.error(f"[任务 {task_id}] 发送消息失败: {e}")
            raise
    
    def process_visual_stream(self, task_id: str, task_message: Dict[str, Any]):
        """
        处理视觉流（Thread A）
        调用视频处理模块，完成后发送Video类型消息
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
        """
        try:
            logger.info(f"[任务 {task_id}] [Thread A] 视觉流处理线程启动，调用视频处理模块")
            
            # 调用视频处理模块的函数
            result = 视频处理.process_video(task_id, task_message)
            
            # 发送Video类型消息
            self.send_result_message(task_id, "video", result)
            
            logger.info(f"[任务 {task_id}] [Thread A] 视觉流处理完成，已发送Video消息")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [Thread A] 视觉流处理失败: {e}", exc_info=True)
            # 发送错误消息
            error_message = {
                "taskId": task_id,
                "moduleType": "error",
                "resultData": {
                    "error": str(e),
                    "failedModule": "video"
                }
            }
            try:
                self.channel.basic_publish(
                    exchange='',
                    routing_key=RESULT_CALLBACK_QUEUE,
                    body=json.dumps(error_message, ensure_ascii=False),
                    properties=pika.BasicProperties(delivery_mode=2)
                )
            except:
                pass
    
    def process_audio_stream(self, task_id: str, task_message: Dict[str, Any]):
        """
        处理音频流（Thread B）
        调用音频处理模块，顺序执行两个步骤：
        Step 1: 提取音频特征 -> 发送Audio类型消息
        Step 2: ASR转文本 -> 发送Text类型消息
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
        """
        try:
            logger.info(f"[任务 {task_id}] [Thread B] 音频流处理线程启动，调用音频处理模块")
            
            # 调用音频处理模块的函数（返回两个结果：audio_result, text_result）
            audio_result, text_result = 音频处理.process_audio(task_id, task_message)
            
            # Step 1: 发送Audio类型消息
            self.send_result_message(task_id, "audio", audio_result)
            logger.info(f"[任务 {task_id}] [Thread B] Step 1完成，已发送Audio消息")
            
            # Step 2: 发送Text类型消息
            self.send_result_message(task_id, "text", text_result)
            logger.info(f"[任务 {task_id}] [Thread B] Step 2完成，已发送Text消息")
            
            logger.info(f"[任务 {task_id}] [Thread B] 音频流处理完成，已发送Audio和Text消息")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [Thread B] 音频流处理失败: {e}", exc_info=True)
            # 发送错误消息
            error_message = {
                "taskId": task_id,
                "moduleType": "error",
                "resultData": {
                    "error": str(e),
                    "failedModule": "audio_stream"
                }
            }
            try:
                self.channel.basic_publish(
                    exchange='',
                    routing_key=RESULT_CALLBACK_QUEUE,
                    body=json.dumps(error_message, ensure_ascii=False),
                    properties=pika.BasicProperties(delivery_mode=2)
                )
            except:
                pass
    
    def process_task(self, task_message: Dict[str, Any]):
        """
        处理单个分析任务
        1. 模拟媒体分割
        2. 启动两个并行线程：
           - Thread A: 处理视觉流（完成后发送Video消息）
           - Thread B: 处理音频流（顺序执行两个步骤，分别发送Audio和Text消息）
        
        Args:
            task_message: 任务消息，包含 taskId, videoId 等信息
        """
        task_id = task_message.get("taskId")
        if not task_id:
            logger.error("任务消息缺少 taskId")
            return
        
        logger.info(f"[任务 {task_id}] ========== 开始处理分析任务 ==========")
        
        try:
            # Step 1: 模拟媒体分割
            split_result = self.simulate_media_splitting(task_id, task_message)
            logger.info(f"[任务 {task_id}] 媒体分割完成: {split_result}")
            
            # Step 2: 启动两个并行线程处理流
            logger.info(f"[任务 {task_id}] 启动两个并行线程处理视觉流和音频流...")
            
            # Thread A: 视觉流处理线程
            visual_thread = threading.Thread(
                target=self.process_visual_stream,
                args=(task_id, task_message),
                name=f"VisualStream-{task_id}"
            )
            
            # Thread B: 音频流处理线程
            audio_thread = threading.Thread(
                target=self.process_audio_stream,
                args=(task_id, task_message),
                name=f"AudioStream-{task_id}"
            )
            
            # 同时启动两个线程
            visual_thread.start()
            audio_thread.start()
            logger.info(f"[任务 {task_id}] 两个线程已启动: VisualStream, AudioStream")
            
            # 等待两个线程完成
            visual_thread.join()
            audio_thread.join()
            
            logger.info(f"[任务 {task_id}] ========== Python端处理完成，已发送3条消息（Video, Audio, Text） ==========")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] 处理任务失败: {e}", exc_info=True)
    
    def on_task_received(self, ch, method, properties, body):
        """
        处理接收到的任务消息（RabbitMQ回调）
        """
        try:
            message_str = body.decode('utf-8')
            task_message = json.loads(message_str)
            task_id = task_message.get('taskId')
            logger.info(f"收到新任务: taskId={task_id}")
            
            # 处理任务
            self.process_task(task_message)
            
            # 确认消息已处理
            ch.basic_ack(delivery_tag=method.delivery_tag)
            
        except Exception as e:
            logger.error(f"处理任务消息失败: {e}", exc_info=True)
            # 拒绝消息并重新入队（可选）
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
    
    def start_consuming(self):
        """开始监听任务分发队列"""
        logger.info("=" * 60)
        logger.info("算法模拟器启动")
        logger.info(f"监听队列: {TASK_DISPATCH_QUEUE}")
        logger.info(f"结果队列: {RESULT_CALLBACK_QUEUE}")
        logger.info("=" * 60)
        
        # 设置每次只接收一条消息
        self.channel.basic_qos(prefetch_count=1)
        
        # 开始消费消息
        self.channel.basic_consume(
            queue=TASK_DISPATCH_QUEUE,
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
        logger.error(f"运行错误: {e}", exc_info=True)
    finally:
        simulator.close()


if __name__ == '__main__':
    main()
