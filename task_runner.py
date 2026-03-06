#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
子进程 Worker — 每个 taskId 对应一个独立的 Process
...
"""

import os
import sys
import json
import time
import logging
import urllib.parse
import pika
from typing import Dict, Any

# 导入配置
try:
    from config import Config
    _deepseek_key = Config.get("DEEPSEEK_API_KEY", "")
    if _deepseek_key and _deepseek_key.strip() and _deepseek_key != "填写你的 DeepSeek API Key":
        print(f"[Config] DEEPSEEK_API_KEY 已就绪（来自 backend/config.py）")
    else:
        print(f"[Config] DEEPSEEK_API_KEY 未设置，请检查 backend/config.py")
except ImportError:
    print(f"[Config] 未找到 backend/config.py，DeepSeek 将不可用")

# ──────────────────────────── 配置 ────────────────────────────
RABBITMQ_HOST = '192.168.253.128'
RABBITMQ_PORT = 5672
RABBITMQ_USERNAME = 'guest'
RABBITMQ_PASSWORD = 'guest'
RABBITMQ_VIRTUAL_HOST = '/'
RESULT_CALLBACK_QUEUE = 'algorithm.result.queue'

TUNNEL_HOST = "5aedd2d8.r12.cpolar.top"
INTERNAL_HOST = "192.168.253.128:9000"
# 是否强制把 MinIO 预签名 URL 改写为隧道域名（默认关闭）
# 说明：预签名 URL 的 host/query 对鉴权敏感，改写后很容易出现 403/404。
USE_TUNNEL_VIDEO_URL = os.getenv("USE_TUNNEL_VIDEO_URL", "0").strip().lower() in {
    "1", "true", "yes", "on"
}


# ──────────────────────────── 子进程日志 ────────────────────────────

def _setup_worker_logger(task_id: str) -> logging.Logger:
    """为子进程创建独立的 logger，带 PID 和 taskId 前缀"""
    logger = logging.getLogger(f"worker.{task_id}")
    logger.setLevel(logging.INFO)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(logging.Formatter(
            f'%(asctime)s [Worker PID={os.getpid()} task={task_id}] %(levelname)s - %(message)s'
        ))
        logger.addHandler(handler)
    return logger


# ──────────────────────────── RabbitMQ 发送 ────────────────────────────

class ResultSender:
    """
    子进程专用的 RabbitMQ 结果发送器。
    在子进程内部创建独立连接，不复用主进程的连接。
    """

    def __init__(self, task_id: str, logger: logging.Logger):
        self.task_id = task_id
        self.logger = logger
        self.connection = None
        self.channel = None
        self._connect()

    def _connect(self):
        credentials = pika.PlainCredentials(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
        params = pika.ConnectionParameters(
            host=RABBITMQ_HOST, port=RABBITMQ_PORT,
            virtual_host=RABBITMQ_VIRTUAL_HOST, credentials=credentials,
            connection_attempts=3, retry_delay=2
        )
        self.connection = pika.BlockingConnection(params)
        self.channel = self.connection.channel()
        self.channel.queue_declare(queue=RESULT_CALLBACK_QUEUE, durable=True)
        self.logger.info("子进程 RabbitMQ 连接已建立")

    def send(self, module_type: str, result_data: dict, retry_count: int = 0):
        """发送模块结果消息，支持重试"""
        message = {
            "taskId": self.task_id,
            "moduleType": module_type,
            "resultData": result_data
        }
        max_retries = 3
        try:
            if not self.connection or self.connection.is_closed:
                self.logger.warning("连接已断开，重连中...")
                self._connect()

            self.channel.basic_publish(
                exchange='',
                routing_key=RESULT_CALLBACK_QUEUE,
                body=json.dumps(message, ensure_ascii=False),
                properties=pika.BasicProperties(delivery_mode=2)
            )
            self.logger.info(f"已发送 {module_type} 结果消息")
        except (pika.exceptions.AMQPConnectionError, pika.exceptions.ChannelClosed) as e:
            if retry_count < max_retries:
                self.logger.warning(f"发送失败，{2 ** retry_count}s 后重试 ({retry_count + 1}/{max_retries}): {e}")
                time.sleep(2 ** retry_count)
                self._connect()
                return self.send(module_type, result_data, retry_count + 1)
            raise
        except Exception as e:
            self.logger.error(f"发送消息失败: {e}", exc_info=True)
            raise

    def close(self):
        try:
            if self.connection and not self.connection.is_closed:
                self.connection.close()
        except Exception:
            pass


# ──────────────────────────── URL 转换 ────────────────────────────

def convert_url_to_tunnel(original_url: str) -> str:
    if not original_url:
        return original_url
    try:
        parsed = urllib.parse.urlparse(original_url)
        decoded_path = urllib.parse.unquote(parsed.path)
        return urllib.parse.urlunparse(("http", TUNNEL_HOST, decoded_path, "", "", ""))
    except Exception:
        return original_url


# ═══════════════════════════════════════════════════════════════
#  分析流水线（从 algorithm_simulator.py 迁移，去掉 threading）
# ═══════════════════════════════════════════════════════════════

def _step_media_split(task_id: str, video_info: dict, logger) -> dict:
    """Step 1: 媒体分割"""
    logger.info("Step 1: 开始媒体分割...")
    process_time = 1.2
    time.sleep(process_time)
    result = {
        "visualStreamReady": True,
        "audioStreamReady": True,
        "splitDuration": round(process_time, 2),
        "videoFilePath": video_info.get("videoUrl", ""),
        "fileSize": video_info.get("fileSize", 0)
    }
    logger.info(f"Step 1: 媒体分割完成 ({process_time:.2f}s)")
    return result


def _step_video_analysis(task_id: str, task_message: dict, logger) -> dict:
    """Step 2a: 视频分析（调用视频处理模块）"""
    logger.info("Step 2a: 开始视频分析...")
    import 视频处理
    result = 视频处理.process_video(task_id, task_message)
    logger.info(f"Step 2a: 视频分析完成 ({result.get('processingTime', 0):.2f}s)")
    return result


def _step_audio_analysis(task_id: str, task_message: dict, logger):
    """Step 2b: 音频分析（调用音频处理模块）"""
    logger.info("Step 2b: 开始音频分析...")
    import 音频处理
    audio_result, text_result = 音频处理.process_audio(task_id, task_message)
    logger.info("Step 2b: 音频分析完成")
    return audio_result, text_result


def _calculate_modality_fusion(dimension: str, v: int, a: int, t: int) -> dict:
    total = v + a + t or 1
    vc = round((v / total) * 100, 1)
    ac = round((a / total) * 100, 1)
    tc = round((t / total) * 100, 1)
    final = int((v * vc + a * ac + t * tc) / 100)
    return {
        "videoScore": v, "audioScore": a, "textScore": t,
        "videoContribution": vc, "audioContribution": ac, "textContribution": tc,
        "finalScore": min(100, max(0, final))
    }


def _get_identity_label(score):
    if score >= 80: return "疑似在校学生"
    elif score >= 60: return "可能为学生"
    else: return "身份不明"


def _build_university_profile(score):
    """
    生成高校关联结果（同时返回布尔值与名称）：
    - isUniversityRelated: 由融合分数直接判定，避免 Java 回退到关键词推断
    - universityName: 永不为空；未涉及时返回固定文案“未涉及具体高校”
    """
    # 统一口径：>=65 视为涉及高校
    is_related = score >= 65
    if is_related:
        return {
            "isUniversityRelated": True,
            "universityName": "高校相关（未识别具体学校）"
        }

    return {
        "isUniversityRelated": False,
        "universityName": "未涉及具体高校"
    }


def _get_topic_sub_category(topic_category):
    sub_categories = {
        "校园生活": ["日常生活", "宿舍趣事", "校园风景"],
        "学术讨论": ["课程学习", "考试经验", "学术研究"],
        "校园政策": ["选课制度吐槽", "宿舍管理", "食堂服务"],
        "社团活动": ["社团招新", "文艺演出", "社团日常"],
        "就业指导": ["实习分享", "求职经验", "职业规划"],
        "科技创新": ["项目展示", "技术分享", "创新创业"],
        "体育运动": ["运动比赛", "健身日常", "体育活动"],
        "艺术表演": ["音乐表演", "舞蹈展示", "艺术创作"],
        "心理健康": ["心理调适", "情绪管理", "压力释放"],
        "社会实践": ["志愿服务", "社会调研", "公益活动"]
    }
    return sub_categories.get(topic_category, ["其他"])[0]


# 风险等级阈值（与要求文档一致：综合风险评分 >0.7 高风险，>0.4 中风险）
RISK_THRESHOLD_HIGH = 70   # >70% 触发高风险预警
RISK_THRESHOLD_MEDIUM = 40  # >40% 触发中风险提醒


def _get_risk_reason(score):
    if score >= RISK_THRESHOLD_HIGH:
        return "可能引发跟风吐槽"
    if score >= RISK_THRESHOLD_MEDIUM:
        return "存在一定传播风险"
    return "风险较低"


def _get_action_suggestion(score):
    if score >= 75:
        return "禁止发布"
    if score >= RISK_THRESHOLD_HIGH:  # >=70
        return "谨慎发布"
    if score >= RISK_THRESHOLD_MEDIUM:  # >=40
        return "可以发布"
    return "推荐发布"


def _get_action_detail(score):
    if score >= 75:
        return "检测到高风险内容，建议禁止上传到公开平台"
    if score >= RISK_THRESHOLD_HIGH:
        return "建议人工复核后决定是否上传"
    if score >= RISK_THRESHOLD_MEDIUM:
        return "内容相对安全，可以发布但建议关注后续反馈"
    return "内容健康，可以放心发布"


def _to_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _calc_segment_count(duration: float, time_granularity: int) -> int:
    safe_duration = max(0.0, _to_float(duration, 0.0))
    safe_granularity = max(1, int(time_granularity or 1))
    # 0s 也至少要有一个时间点（t=0）
    return max(1, int(safe_duration / safe_granularity) + 1)


def _normalize_timeline_series(series: Any, expected_len: int, fallback_reason: str) -> list:
    """
    将任意模态时间序列对齐到 expected_len：
    - 长度不足：沿用最后一个有效点（而不是补 0，避免尾部曲线断崖）
    - 长度过长：截断
    """
    if not isinstance(series, list):
        series = []

    normalized = []
    last_intensity = 0.0
    last_reason = fallback_reason

    for i in range(expected_len):
        raw = series[i] if i < len(series) else None
        if isinstance(raw, dict):
            intensity = max(0.0, min(1.0, _to_float(raw.get("intensity"), last_intensity)))
            reason = raw.get("reason") or last_reason
            last_intensity = intensity
            last_reason = reason
        else:
            intensity = last_intensity
            reason = last_reason

        normalized.append({
            "intensity": round(intensity, 2),
            "reason": reason
        })

    return normalized


def _normalize_fusion_weights(video_w: float, audio_w: float, text_w: float) -> tuple:
    """将融合权重归一化到 [0,1] 且总和为 1。"""
    vw = max(0.0, _to_float(video_w, 0.0))
    aw = max(0.0, _to_float(audio_w, 0.0))
    tw = max(0.0, _to_float(text_w, 0.0))

    total = vw + aw + tw
    if total <= 0:
        return 1 / 3, 1 / 3, 1 / 3

    return vw / total, aw / total, tw / total


def _step_integration(task_id: str, video_result: dict, audio_result: dict,
                      text_result: dict, logger) -> dict:
    """Step 4: 综合分析（融合计算 + 雷达图）"""
    logger.info("Step 4: 开始综合分析...")
    start_time = time.time()

    video_features = video_result.get("features", {})
    audio_features = audio_result.get("features", {})
    text_features = text_result.get("features", {})

    v_scores = video_features.get("visualRiskScores", {})
    a_scores = audio_features.get("audioRiskScores", {})
    t_scores = text_features.get("textRiskScores", {})

    duration = _to_float(video_features.get("duration"), 0.0)
    if duration <= 0:
        duration = 60.0
    time_granularity = 5
    num_segments = _calc_segment_count(duration, time_granularity)

    # 6 维融合
    dimensions = ["identity", "university", "topic", "attitude", "opinionRisk", "action"]
    fusion = {}
    for dim in dimensions:
        fusion[dim] = _calculate_modality_fusion(
            dim, v_scores.get(dim, 0), a_scores.get(dim, 0), t_scores.get(dim, 0)
        )

    # 综合风险时间序列
    video_risks = _normalize_timeline_series(
        video_result.get("videoRisks", []),
        num_segments,
        "画面数据不足，沿用上一个有效值"
    )
    audio_emotions = _normalize_timeline_series(
        audio_result.get("audioEmotions", []),
        num_segments,
        "音频数据不足，沿用上一个有效值"
    )
    text_risks = _normalize_timeline_series(
        text_result.get("textRisks", []),
        num_segments,
        "文本数据不足，沿用上一个有效值"
    )

    logger.info(
        "Step 4: 时间轴长度对齐完成 duration=%.2fs granularity=%ss segments=%s (video=%s audio=%s text=%s)",
        duration,
        time_granularity,
        num_segments,
        len(video_risks),
        len(audio_emotions),
        len(text_risks),
    )

    # 使用 opinionRisk 的多模态贡献度作为时间轴融合权重（避免“综合风险=单一模态”）
    op_fusion = fusion.get("opinionRisk", {})
    w_video, w_audio, w_text = _normalize_fusion_weights(
        _to_float(op_fusion.get("videoContribution"), 33.3),
        _to_float(op_fusion.get("audioContribution"), 33.3),
        _to_float(op_fusion.get("textContribution"), 33.3),
    )

    logger.info(
        "Step 4: 时间轴融合权重 video=%.3f audio=%.3f text=%.3f",
        w_video,
        w_audio,
        w_text,
    )

    comprehensive_risks = []
    for i in range(num_segments):
        vi = video_risks[i]["intensity"]
        ai = audio_emotions[i]["intensity"]
        ti = text_risks[i]["intensity"]

        # 先做加权平均，再适当引入峰值（15%）以保留突发风险感知
        weighted = vi * w_video + ai * w_audio + ti * w_text
        peak = max(vi, ai, ti)
        fused = min(1.0, max(0.0, weighted * 0.85 + peak * 0.15))

        comprehensive_risks.append({"intensity": round(fused, 2)})

    # 雷达图时间序列
    radar_by_time = []
    for i in range(num_segments):
        sr = comprehensive_risks[i]["intensity"]
        radar_data = [
            int(fusion["identity"]["finalScore"] * (0.8 + sr * 0.4)),
            int(fusion["university"]["finalScore"] * (0.8 + sr * 0.4)),
            int(fusion["attitude"]["finalScore"] * (0.7 + sr * 0.6)),
            int(fusion["topic"]["finalScore"] * (0.75 + sr * 0.5)),
            int(fusion["opinionRisk"]["finalScore"] * (0.7 + sr * 0.6)),
            int(fusion["action"]["finalScore"] * (0.75 + sr * 0.5))
        ]
        radar_data = [min(100, max(0, v)) for v in radar_data]
        radar_by_time.append({"data": radar_data})

    # 平均雷达
    avg_radar = [0] * 6
    for r in radar_by_time:
        for j, v in enumerate(r["data"]):
            avg_radar[j] += v
    avg_radar = [int(v / len(radar_by_time)) for v in avg_radar]

    university_profile = _build_university_profile(fusion["university"]["finalScore"])

    integration_result = {
        "identity": {
            "identityLabel": _get_identity_label(fusion["identity"]["finalScore"]),
            "modalityFusion": fusion["identity"]
        },
        "university": {
            "isUniversityRelated": university_profile["isUniversityRelated"],
            "universityName": university_profile["universityName"],
            "modalityFusion": fusion["university"]
        },
        "topic": {
            "topicCategory": text_features.get("topicCategory", "校园生活"),
            "topicSubCategory": _get_topic_sub_category(text_features.get("topicCategory", "")),
            "modalityFusion": fusion["topic"]
        },
        "opinionRisk": {
            "riskReason": _get_risk_reason(fusion["opinionRisk"]["finalScore"]),
            "modalityFusion": fusion["opinionRisk"]
        },
        "action": {
            "actionSuggestion": _get_action_suggestion(fusion["action"]["finalScore"]),
            "actionDetail": _get_action_detail(fusion["action"]["finalScore"]),
            "modalityFusion": fusion["action"]
        },
        "timelineData": {
            "comprehensiveRisks": comprehensive_risks,
            "radarByTime": radar_by_time,
            "averageRadarData": avg_radar
        },
        "processingTime": round(time.time() - start_time, 2)
    }

    logger.info(f"Step 4: 综合分析完成 ({integration_result['processingTime']:.2f}s)")
    return integration_result


# ═══════════════════════════════════════════════════════════════
#  子进程入口
# ═══════════════════════════════════════════════════════════════

def run_task(task_id: str, task_message: dict):
    """
    子进程入口函数 —— 由 multiprocessing.Process 调用。
    
    完整执行 5 步分析流水线：
      1. 媒体分割
      2a. 视频分析 → 发送 video 消息
      2b. 音频分析 → 发送 audio + text 消息
      3. 综合分析 → 发送 integration 消息
    
    注意：视频和音频在子进程内串行执行（不再用线程并行），
    因为子进程本身就是独立的，不需要线程来隔离。
    如果需要并行，可以在子进程内再 fork，但当前场景串行即可。
    """
    logger = _setup_worker_logger(task_id)
    logger.info(f"========== 子进程启动 PID={os.getpid()} ==========")

    # 输出任务携带的风险词库包信息（确认 Python 端能看到词汇包）
    word_packs = task_message.get("wordPacks")
    if word_packs:
        logger.info(f"[风险词库] 任务携带 {len(word_packs)} 个词库包:")
        for pack in word_packs:
            pack_id = pack.get("packId", "unknown")
            words = pack.get("words", [])
            logger.info(f"  - 词库包 {pack_id}: {len(words)} 个词汇 → {words}")
    else:
        logger.info("[风险词库] 任务未挂载任何词库包")

    sender = None
    try:
        # 初始化独立的 RabbitMQ 连接
        sender = ResultSender(task_id, logger)

        # URL 准备
        original_url = task_message.get("videoUrl")
        if original_url:
            task_message["videoUrlInternal"] = original_url
            # 默认保留原始 URL 参与分析；隧道 URL 仅用于调试排查
            tunnel_url = convert_url_to_tunnel(original_url)
            task_message["videoUrlTunnel"] = tunnel_url

            if USE_TUNNEL_VIDEO_URL:
                task_message["videoUrl"] = tunnel_url
                logger.info("已启用隧道URL改写: videoUrl -> %s", tunnel_url)
            else:
                logger.info("保留原始视频URL用于分析，隧道URL仅调试使用")

        # Step 1: 媒体分割
        _step_media_split(task_id, task_message, logger)

        # Step 2a: 视频分析
        video_result = _step_video_analysis(task_id, task_message, logger)
        sender.send("video", video_result)

        # 将视频真实时长回填给后续音频/文本模块，确保时间轴长度与视频一致
        video_duration = _to_float(video_result.get("features", {}).get("duration"), 0.0)
        if video_duration > 0:
            task_message["videoDuration"] = video_duration
            logger.info("已回填视频真实时长给音频模块: %.2fs", video_duration)

        # Step 2b: 音频分析
        audio_result, text_result = _step_audio_analysis(task_id, task_message, logger)
        sender.send("audio", audio_result)
        sender.send("text", text_result)

        # Step 3: 综合分析
        integration_result = _step_integration(
            task_id, video_result, audio_result, text_result, logger
        )
        sender.send("integration", integration_result)

        logger.info("========== 子进程任务完成，已发送 4 条消息 ==========")

    except Exception as e:
        logger.error(f"子进程执行失败: {e}", exc_info=True)
        # 尝试发送错误消息
        if sender:
            try:
                sender.send("error", {
                    "error": str(e),
                    "failedModule": "worker",
                    "pid": os.getpid()
                })
            except Exception:
                pass
    finally:
        if sender:
            sender.close()
        logger.info(f"子进程 PID={os.getpid()} 退出")
