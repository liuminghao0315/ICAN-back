#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
七牛云算法模拟器
使用七牛云API替换原有的模拟算法，实现真实的内容审核功能
"""

import json
import time
import logging
import threading
from typing import Dict, Any

# 导入七牛云处理模块
from qiniu_video_processor import process_video as qiniu_process_video
from qiniu_audio_processor import extract_audio_features as qiniu_extract_audio_features
from qiniu_text_processor import convert_audio_to_text as qiniu_convert_audio_to_text

# 导入原有的RabbitMQ相关模块
import pika

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# RabbitMQ 配置（与原有配置保持一致）
RABBITMQ_HOST = '192.168.6.130'
RABBITMQ_PORT = 5672
RABBITMQ_USERNAME = 'guest'
RABBITMQ_PASSWORD = 'guest'
RABBITMQ_VIRTUAL_HOST = '/'

# 队列名称（与原有配置保持一致）
TASK_DISPATCH_QUEUE = 'algorithm.task.queue'  # 从 Java 接收任务
RESULT_CALLBACK_QUEUE = 'algorithm.result.queue'  # 向 Java 发送结果


class QiniuAlgorithmSimulator:
    """七牛云算法模拟器 - 使用七牛云API替换模拟算法"""
    
    def __init__(self):
        """初始化 RabbitMQ 连接"""
        self.connection = None
        self.channel = None
        self.connect()
    
    def connect(self, retry_count: int = 0, max_retries: int = 5):
        """
        连接到 RabbitMQ
        支持自动重连机制
        
        Args:
            retry_count: 当前重试次数
            max_retries: 最大重试次数
        """
        try:
            credentials = pika.PlainCredentials(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
            parameters = pika.ConnectionParameters(
                host=RABBITMQ_HOST,
                port=RABBITMQ_PORT,
                virtual_host=RABBITMQ_VIRTUAL_HOST,
                credentials=credentials,
                connection_attempts=3,  # 连接尝试次数
                retry_delay=2  # 重试延迟（秒）
            )
            self.connection = pika.BlockingConnection(parameters)
            self.channel = self.connection.channel()
            
            # 声明队列
            self.channel.queue_declare(queue=TASK_DISPATCH_QUEUE, durable=True)
            self.channel.queue_declare(queue=RESULT_CALLBACK_QUEUE, durable=True)
            
            logger.info("成功连接到 RabbitMQ")
        except pika.exceptions.AMQPConnectionError as e:
            logger.error(f"连接 RabbitMQ 失败: {e}")
            if retry_count < max_retries:
                wait_time = (retry_count + 1) * 2  # 递增等待时间
                logger.info(f"等待 {wait_time} 秒后重试连接... (尝试 {retry_count + 1}/{max_retries})")
                time.sleep(wait_time)
                return self.connect(retry_count + 1, max_retries)
            else:
                logger.error(f"达到最大重试次数 ({max_retries})，连接失败")
                raise
        except Exception as e:
            logger.error(f"连接 RabbitMQ 时发生未知错误: {e}", exc_info=True)
            raise
    
    def send_result_message(self, task_id: str, module_type: str, result_data: Dict[str, Any], retry_count: int = 0):
        """
        发送模块完成消息到结果回调队列
        支持自动重试机制
        
        Args:
            task_id: 任务ID
            module_type: 模块类型（video/audio/text）
            result_data: 该模块的分析结果数据
            retry_count: 当前重试次数
        """
        message = {
            "taskId": task_id,
            "moduleType": module_type,
            "resultData": result_data
        }
        
        max_retries = 3
        try:
            # 检查连接状态
            if not self.connection or self.connection.is_closed:
                logger.warning(f"[任务 {task_id}] RabbitMQ连接已断开，尝试重连...")
                self.connect()
            
            self.channel.basic_publish(
                exchange='',
                routing_key=RESULT_CALLBACK_QUEUE,
                body=json.dumps(message, ensure_ascii=False),
                properties=pika.BasicProperties(
                    delivery_mode=2,  # 消息持久化
                )
            )
            logger.info(f"[任务 {task_id}] 已发送模块结果消息: {module_type}")
        except (pika.exceptions.AMQPConnectionError, pika.exceptions.ChannelClosed) as e:
            logger.error(f"[任务 {task_id}] 发送消息失败（连接错误）: {e}")
            if retry_count < max_retries:
                logger.info(f"[任务 {task_id}] 等待 {2 ** retry_count} 秒后重试发送... (尝试 {retry_count + 1}/{max_retries})")
                time.sleep(2 ** retry_count)  # 指数退避
                try:
                    self.connect()
                    return self.send_result_message(task_id, module_type, result_data, retry_count + 1)
                except Exception as reconnect_error:
                    logger.error(f"[任务 {task_id}] 重连失败: {reconnect_error}")
                    raise
            else:
                logger.error(f"[任务 {task_id}] 达到最大重试次数 ({max_retries})，发送失败")
                raise
        except Exception as e:
            logger.error(f"[任务 {task_id}] 发送消息失败: {e}", exc_info=True)
            raise
    
    def process_visual_stream(self, task_id: str, task_message: Dict[str, Any]):
        """
        处理视觉流（Thread A）
        调用七牛云视频审核模块，完成后发送Video类型消息
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
        """
        try:
            logger.info(f"[任务 {task_id}] [Thread A] 视觉流处理线程启动，调用七牛云视频审核模块")
            
            # 调用七牛云视频审核模块
            result = qiniu_process_video(task_id, task_message)
            
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
                    "failedModule": "video",
                    "source": "qiniu_video_censor"
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
        调用七牛云音频和文本审核模块，顺序执行两个步骤：
        Step 1: 音频审核 -> 发送Audio类型消息
        Step 2: 文本审核 -> 发送Text类型消息
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
        """
        try:
            logger.info(f"[任务 {task_id}] [Thread B] 音频流处理线程启动，调用七牛云音频和文本审核模块")
            
            # Step 1: 调用七牛云音频审核模块
            audio_result = qiniu_extract_audio_features(task_id, task_message)
            
            # 发送Audio类型消息
            self.send_result_message(task_id, "audio", audio_result)
            logger.info(f"[任务 {task_id}] [Thread B] Step 1完成，已发送Audio消息")
            
            # Step 2: 调用七牛云文本审核模块
            text_result = qiniu_convert_audio_to_text(task_id, task_message)
            
            # 发送Text类型消息
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
                    "failedModule": "audio_stream",
                    "source": "qiniu_audio_text_censor"
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
        1. 启动两个并行线程：
           - Thread A: 处理视觉流（调用七牛云视频审核，完成后发送Video消息）
           - Thread B: 处理音频流（顺序执行两个步骤，分别发送Audio和Text消息）
        
        Args:
            task_message: 任务消息，包含 taskId, videoId 等信息
        """
        task_id = task_message.get("taskId")
        if not task_id:
            logger.error("任务消息缺少 taskId")
            return
        
        logger.info(f"[任务 {task_id}] ========== 开始处理分析任务（使用七牛云API） ==========")
        
        try:
            # 启动两个并行线程处理流
            logger.info(f"[任务 {task_id}] 启动两个并行线程处理视觉流和音频流...")
            
            # Thread A: 视觉流处理线程
            visual_thread = threading.Thread(
                target=self.process_visual_stream,
                args=(task_id, task_message),
                name=f"QiniuVisualStream-{task_id}"
            )
            
            # Thread B: 音频流处理线程
            audio_thread = threading.Thread(
                target=self.process_audio_stream,
                args=(task_id, task_message),
                name=f"QiniuAudioStream-{task_id}"
            )
            
            # 同时启动两个线程
            visual_thread.start()
            audio_thread.start()
            logger.info(f"[任务 {task_id}] 两个线程已启动: QiniuVisualStream, QiniuAudioStream")
            
            # 等待两个线程完成
            visual_thread.join()
            audio_thread.join()
            
            logger.info(f"[任务 {task_id}] ========== 七牛云API处理完成，已发送3条消息（Video, Audio, Text） ==========")
            
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
            logger.info(f"收到新任务（七牛云处理）: taskId={task_id}")
            
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
        logger.info("七牛云算法模拟器启动")
        logger.info("使用七牛云API进行真实的内容审核")
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
    simulator = QiniuAlgorithmSimulator()
    try:
        simulator.start_consuming()
    except Exception as e:
        logger.error(f"运行错误: {e}", exc_info=True)
    finally:
        simulator.close()


if __name__ == '__main__':
    main()