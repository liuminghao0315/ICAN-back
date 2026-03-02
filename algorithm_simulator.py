#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频分析算法主模块（多进程架构）

架构：
  主进程（本文件）  ─── 监听 task queue / cancel queue
       │
       └── 子进程 (task_runner.py)  ─── 每个 taskId 一个独立 Process
                                        拥有独立 RabbitMQ 连接
                                        可被 SIGKILL 物理强杀
"""

import os
import sys
import json
import time
import signal
import logging
import threading
import pika
from multiprocessing import Process
from typing import Dict

from task_runner import run_task

# ──────────────────────────── 日志 ────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [主进程 %(process)d] %(levelname)s - %(message)s'
)
logger = logging.getLogger("algorithm_simulator")

# ──────────────────────────── 配置 ────────────────────────────
RABBITMQ_HOST = '192.168.253.128'
RABBITMQ_PORT = 5672
RABBITMQ_USERNAME = 'guest'
RABBITMQ_PASSWORD = 'guest'
RABBITMQ_VIRTUAL_HOST = '/'

TASK_DISPATCH_QUEUE = 'algorithm.task.queue'
RESULT_CALLBACK_QUEUE = 'algorithm.result.queue'
CANCEL_QUEUE = 'algorithm.cancel.queue'

# ──────────────────────────── 任务注册表 ────────────────────────────
active_tasks: Dict[str, Process] = {}
_lock = threading.Lock()


# ═══════════════════════════════════════════════════════════════
#  子进程管理
# ═══════════════════════════════════════════════════════════════

def _force_kill_process(proc: Process, task_id: str) -> None:
    if proc is None or not proc.is_alive():
        logger.info(f"[{task_id}] 子进程已不存在或已退出，无需强杀")
        return

    pid = proc.pid
    logger.warning(f"[{task_id}] 正在强杀子进程 PID={pid} ...")

    try:
        if sys.platform == 'win32':
            os.kill(pid, signal.SIGTERM)
        else:
            os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        logger.info(f"[{task_id}] PID={pid} 已不存在")
    except Exception as e:
        logger.error(f"[{task_id}] 强杀 PID={pid} 失败: {e}")

    try:
        proc.join(timeout=3)
    except Exception:
        pass

    logger.info(f"[{task_id}] 子进程 PID={pid} 已被终止")


def _cleanup_temp_files(task_id: str) -> None:
    audio_path = os.path.join("audio_files", f"audio_{task_id}.wav")
    if os.path.exists(audio_path):
        try:
            os.remove(audio_path)
            logger.info(f"[{task_id}] 已清理临时音频文件: {audio_path}")
        except Exception as e:
            logger.warning(f"[{task_id}] 清理临时文件失败: {e}")


def _send_abort_notification(task_id: str) -> None:
    abort_message = {
        "taskId": task_id,
        "moduleType": "abort",
        "resultData": {
            "status": "TASK_ABORTED_BY_USER",
            "reason": "用户取消任务，子进程已被物理终止",
            "timestamp": time.time()
        }
    }
    try:
        credentials = pika.PlainCredentials(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
        params = pika.ConnectionParameters(
            host=RABBITMQ_HOST, port=RABBITMQ_PORT,
            virtual_host=RABBITMQ_VIRTUAL_HOST, credentials=credentials
        )
        conn = pika.BlockingConnection(params)
        ch = conn.channel()
        ch.basic_publish(
            exchange='',
            routing_key=RESULT_CALLBACK_QUEUE,
            body=json.dumps(abort_message, ensure_ascii=False),
            properties=pika.BasicProperties(delivery_mode=2)
        )
        conn.close()
        logger.info(f"[{task_id}] 已发送 TASK_ABORTED_BY_USER 到 Java 端")
    except Exception as e:
        logger.error(f"[{task_id}] 发送中止通知失败: {e}")


def cancel_task(task_id: str) -> None:
    with _lock:
        proc = active_tasks.get(task_id)

    if proc is None:
        logger.warning(f"[{task_id}] 注册表中无此任务，可能已完成或从未启动")
        _send_abort_notification(task_id)
        return

    _force_kill_process(proc, task_id)
    _cleanup_temp_files(task_id)
    _send_abort_notification(task_id)

    with _lock:
        active_tasks.pop(task_id, None)

    logger.info(f"[{task_id}] 任务取消流程完成")


def _reap_finished_tasks() -> None:
    with _lock:
        finished = [tid for tid, proc in active_tasks.items() if not proc.is_alive()]
        for tid in finished:
            proc = active_tasks.pop(tid)
            proc.join(timeout=1)
            logger.info(f"[{tid}] 子进程已自然结束，已从注册表移除 (exitcode={proc.exitcode})")


def start_task(task_id: str, task_message: dict) -> None:
    with _lock:
        existing = active_tasks.get(task_id)

    if existing is not None and existing.is_alive():
        logger.warning(f"[{task_id}] 检测到同 ID 残留进程 PID={existing.pid}，先强杀")
        _force_kill_process(existing, task_id)
        _cleanup_temp_files(task_id)
        with _lock:
            active_tasks.pop(task_id, None)

    proc = Process(
        target=run_task,
        args=(task_id, task_message),
        name=f"Worker-{task_id}",
        daemon=False
    )
    proc.start()

    with _lock:
        active_tasks[task_id] = proc

    logger.info(f"[{task_id}] 子进程已启动 PID={proc.pid}")


# ═══════════════════════════════════════════════════════════════
#  子进程监控线程
# ═══════════════════════════════════════════════════════════════

def _monitor_loop():
    while True:
        try:
            _reap_finished_tasks()
        except Exception as e:
            logger.error(f"监控线程异常: {e}")
        time.sleep(5)


# ═══════════════════════════════════════════════════════════════
#  RabbitMQ 回调
# ═══════════════════════════════════════════════════════════════

def on_task_received(ch, method, properties, body):
    try:
        task_message = json.loads(body.decode('utf-8'))
        task_id = task_message.get('taskId')
        if not task_id:
            logger.error("收到的任务消息缺少 taskId，丢弃")
            ch.basic_ack(delivery_tag=method.delivery_tag)
            return

        logger.info(f"收到新任务: taskId={task_id}")
        start_task(task_id, task_message)
        ch.basic_ack(delivery_tag=method.delivery_tag)

    except Exception as e:
        logger.error(f"处理任务消息失败: {e}", exc_info=True)
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)


def on_cancel_received(ch, method, properties, body):
    try:
        cancel_msg = json.loads(body.decode('utf-8'))
        task_id = cancel_msg.get('taskId')
        action = cancel_msg.get('action', '')

        if not task_id:
            logger.warning("取消消息缺少 taskId，丢弃")
            ch.basic_ack(delivery_tag=method.delivery_tag)
            return

        logger.info(f"收到取消信号: taskId={task_id}, action={action}")
        cancel_task(task_id)
        ch.basic_ack(delivery_tag=method.delivery_tag)

    except Exception as e:
        logger.error(f"处理取消消息失败: {e}", exc_info=True)
        ch.basic_ack(delivery_tag=method.delivery_tag)


# ═══════════════════════════════════════════════════════════════
#  主入口
# ═══════════════════════════════════════════════════════════════

def main():
    logger.info("=" * 60)
    logger.info("视频分析算法模拟器启动（multiprocessing 架构）")
    logger.info(f"  任务队列: {TASK_DISPATCH_QUEUE}")
    logger.info(f"  取消队列: {CANCEL_QUEUE}")
    logger.info(f"  结果队列: {RESULT_CALLBACK_QUEUE}")
    logger.info(f"  PID: {os.getpid()}")
    logger.info("=" * 60)

    monitor = threading.Thread(target=_monitor_loop, daemon=True, name="TaskMonitor")
    monitor.start()

    credentials = pika.PlainCredentials(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
    params = pika.ConnectionParameters(
        host=RABBITMQ_HOST,
        port=RABBITMQ_PORT,
        virtual_host=RABBITMQ_VIRTUAL_HOST,
        credentials=credentials,
        connection_attempts=5,
        retry_delay=3,
        heartbeat=600,
        blocked_connection_timeout=300
    )

    connection = pika.BlockingConnection(params)
    channel = connection.channel()

    channel.queue_declare(queue=TASK_DISPATCH_QUEUE, durable=True)
    channel.queue_declare(queue=RESULT_CALLBACK_QUEUE, durable=True)
    channel.queue_declare(queue=CANCEL_QUEUE, durable=True)

    channel.basic_qos(prefetch_count=1)
    channel.basic_consume(queue=TASK_DISPATCH_QUEUE, on_message_callback=on_task_received)
    channel.basic_consume(queue=CANCEL_QUEUE, on_message_callback=on_cancel_received)

    logger.info("等待消息...")

    try:
        channel.start_consuming()
    except KeyboardInterrupt:
        logger.info("收到 Ctrl+C，正在清理所有子进程...")
        with _lock:
            for tid, proc in list(active_tasks.items()):
                _force_kill_process(proc, tid)
                _cleanup_temp_files(tid)
            active_tasks.clear()
        channel.stop_consuming()
        connection.close()
        logger.info("已安全退出")


if __name__ == '__main__':
    main()
