# -*- coding: utf-8 -*-
"""
基于 DeepSeek API 根据转写文本生成六类分析维度的详细证据 JSON。
用于在无训练模型场景下，用语音转文本结果通过大模型补全「身份判定、涉及高校、内容主题、
对学校态度、潜在舆论风险、处置建议」的详细证据，供前端卡片展示。

配置：请在环境变量中设置 DEEPSEEK_API_KEY，勿将密钥写入代码。可参考 backend/.env.example。
"""

import json
import logging
import os
import re
import time
from typing import Any, Dict, List, Optional

# 加载 backend/.env（多路径尝试，确保子进程/脚本都能读到 DEEPSEEK_API_KEY）
def _load_dotenv_for_deepseek():
    try:
        from dotenv import load_dotenv
        _dir = os.path.dirname(os.path.abspath(__file__))
        candidates = [
            os.path.join(_dir, ".env"),
            os.path.join(os.getcwd(), ".env"),
            os.path.join(os.getcwd(), "backend", ".env"),
        ]
        for p in candidates:
            if os.path.isfile(p):
                load_dotenv(p, override=True)
                return
    except Exception:
        pass

_load_dotenv_for_deepseek()

import requests

logger = logging.getLogger(__name__)

DEEPSEEK_API_URL = os.getenv("DEEPSEEK_API_URL", "https://api.deepseek.com/v1/chat/completions")
DEEPSEEK_API_KEY = (os.getenv("DEEPSEEK_API_KEY") or "").strip()
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
DEEPSEEK_TIMEOUT = int(os.getenv("DEEPSEEK_TIMEOUT", "45"))
# 转写文本过短时跳过 API 调用，直接用规则证据
MIN_TRANSCRIPTION_LENGTH = int(os.getenv("DEEPSEEK_MIN_TRANSCRIPTION_LENGTH", "20"))

# 首次导入时打一条，便于确认子进程是否读到 key
if DEEPSEEK_API_KEY:
    print("[DeepSeek] 模块已加载，DEEPSEEK_API_KEY 已就绪")
else:
    print("[DeepSeek] 模块已加载，DEEPSEEK_API_KEY 未设置（请检查 backend/.env 或环境变量）")

# 单条证据的合法字段（与前端 Evidence 接口一致）
EVIDENCE_KEYS = {"timestamp", "type", "description", "confidence", "keyword", "sentimentScore"}
EVIDENCE_TYPES = ("video", "audio", "text")


def _normalize_evidence(ev: Any, duration_sec: float, default_ts: int) -> Optional[Dict[str, Any]]:
    """将模型返回的一条证据规范化为前端/Java 可用的结构。"""
    if not isinstance(ev, dict):
        return None
    ts = ev.get("timestamp")
    if ts is None:
        ts = default_ts
    try:
        ts = int(float(ts))
    except (TypeError, ValueError):
        ts = default_ts
    ts = max(0, min(int(duration_sec), ts)) if duration_sec > 0 else 0

    ev_type = ev.get("type") or "text"
    if ev_type not in EVIDENCE_TYPES:
        ev_type = "text"

    desc = ev.get("description")
    if desc is None:
        desc = ""
    if not isinstance(desc, str):
        desc = str(desc)

    conf = ev.get("confidence")
    if conf is None:
        conf = 75
    try:
        conf = int(float(conf))
    except (TypeError, ValueError):
        conf = 75
    conf = max(0, min(100, conf))

    out = {
        "timestamp": ts,
        "type": ev_type,
        "description": desc[:500],
        "confidence": conf,
    }
    if ev.get("keyword") is not None and str(ev.get("keyword")).strip():
        out["keyword"] = str(ev.get("keyword")).strip()[:100]
    if "sentimentScore" in ev and ev["sentimentScore"] is not None:
        try:
            s = int(float(ev["sentimentScore"]))
            out["sentimentScore"] = max(0, min(100, s))
        except (TypeError, ValueError):
            pass
    return out


def _normalize_evidences_list(arr: Any, duration_sec: float, default_ts: int) -> List[Dict[str, Any]]:
    """规范化一维证据数组。"""
    if not isinstance(arr, list):
        return []
    result = []
    for i, ev in enumerate(arr):
        ts = default_ts if duration_sec <= 0 else int((i / max(1, len(arr))) * duration_sec)
        normalized = _normalize_evidence(ev, duration_sec, ts)
        if normalized:
            result.append(normalized)
    return result


def _ensure_six_dimensions(raw: Dict[str, Any], duration_sec: float) -> Dict[str, List[Dict[str, Any]]]:
    """确保返回的字典包含 6 个维度，且每个维度为证据列表。"""
    keys = ("identity", "university", "topic", "attitude", "opinionRisk", "action")
    default_ts = max(0, int(duration_sec * 0.3)) if duration_sec > 0 else 0
    out = {}
    for k in keys:
        arr = raw.get(k)
        out[k] = _normalize_evidences_list(arr, duration_sec, default_ts)
    return out


SYSTEM_PROMPT = """你是一个高校舆情内容分析助手。根据用户提供的一段「语音转文字」的转写文本，从 6 个维度提取详细证据，并严格按照下方约定的 json 格式输出。请只输出合法 json，不要包含 markdown、代码块或任何说明文字。

输出必须是合法的 json 对象，且仅包含以下 6 个键（键名必须英文）：identity、university、topic、attitude、opinionRisk、action。每个键的值为「证据对象」的数组；若某维度无法从文本推断，则给空数组 []。

每条证据对象的 json 字段说明：
- timestamp：整数（秒），0 到视频时长之间
- type：固定为 "text"
- description：字符串，一句话描述该条证据
- confidence：整数 0-100
- keyword：（可选）字符串
- sentimentScore：（仅 attitude 维度可选）整数 0-100，越高越负面

EXAMPLE JSON OUTPUT（仅作格式参考，请根据实际转写内容生成）：
{
  "identity": [
    { "timestamp": 5, "type": "text", "description": "文中提到「我们班」「学号」等，推断可能为学生", "confidence": 78, "keyword": "我们班" }
  ],
  "university": [
    { "timestamp": 12, "type": "text", "description": "提到校园、食堂等，涉及高校场景", "confidence": 85, "keyword": "校园" }
  ],
  "topic": [
    { "timestamp": 8, "type": "text", "description": "内容围绕选课、教务相关", "confidence": 82, "keyword": "选课" }
  ],
  "attitude": [
    { "timestamp": 20, "type": "text", "description": "表达对学校管理不满", "confidence": 80, "keyword": "不满", "sentimentScore": 72 }
  ],
  "opinionRisk": [
    { "timestamp": 25, "type": "text", "description": "存在负面表述可能引发传播", "confidence": 70, "keyword": "投诉" }
  ],
  "action": [
    { "timestamp": 30, "type": "text", "description": "建议人工复核后决定是否发布", "confidence": 75, "keyword": "复核" }
  ]
}

请根据用户提供的转写文本，按上述 json 格式直接输出一个 json 对象，不要用 ``` 包裹。"""


def _extract_json_from_content(content: str) -> Optional[Dict[str, Any]]:
    """从模型返回的 content 中提取 JSON。支持被 ```json ... ``` 包裹的情况。"""
    if not content or not content.strip():
        return None
    text = content.strip()
    # 去掉 markdown 代码块
    m = re.search(r"```(?:json)?\s*([\s\S]*?)```", text)
    if m:
        text = m.group(1).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    end = text.rfind("}") + 1
    if start >= 0 and end > start:
        try:
            return json.loads(text[start:end])
        except json.JSONDecodeError:
            pass
    return None


def generate_evidences_via_deepseek(
    transcription: str,
    duration_sec: float,
    task_id: Optional[str] = None,
) -> Optional[Dict[str, List[Dict[str, Any]]]]:
    """
    根据转写文本调用 DeepSeek API，生成六类分析维度的详细证据 JSON。

    Args:
        transcription: 语音转文字得到的全文。
        duration_sec: 视频/音频时长（秒），用于生成合理的时间戳。
        task_id: 可选任务 ID，用于日志。

    Returns:
        与 generate_preliminary_evidences 同结构的字典：
        { "identity": [...], "university": [...], "topic": [...],
          "attitude": [...], "opinionRisk": [...], "action": [...] }
        若未配置 API Key、文本过短、或请求失败则返回 None。
    """
    if not DEEPSEEK_API_KEY or not DEEPSEEK_API_KEY.strip():
        msg = "DEEPSEEK_API_KEY 未配置，跳过 DeepSeek 证据生成"
        logger.debug(msg)
        print(f"[DeepSeek] 未使用：{msg}")
        return None

    text = (transcription or "").strip()
    if len(text) < MIN_TRANSCRIPTION_LENGTH:
        msg = f"转写文本过短（{len(text)} 字 < {MIN_TRANSCRIPTION_LENGTH} 字），跳过 DeepSeek"
        logger.debug(msg)
        print(f"[DeepSeek] 未使用：{msg}")
        return None

    duration_sec = max(0.0, float(duration_sec))
    duration_int = int(duration_sec)
    print(f"[DeepSeek] 开始请求 API（转写 {len(text)} 字，时长 {duration_int}s，任务 {task_id or '-'}）...")

    user_content = f"""请根据下面的转写文本，按 system 中约定的 json 格式输出 6 个维度的详细证据。视频时长约 {duration_int} 秒，timestamp 请分布在 0 到 {duration_int} 之间。

转写文本：
---
{text[:12000]}
---

直接输出一个 json 对象，包含 identity、university、topic、attitude、opinionRisk、action 六个键，每个键为证据数组。"""

    payload = {
        "model": DEEPSEEK_MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0.3,
        "max_tokens": 4096,
    }

    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {DEEPSEEK_API_KEY.strip()}",
    }

    log_prefix = f"[任务 {task_id}] " if task_id else ""
    start = time.time()
    try:
        resp = requests.post(
            DEEPSEEK_API_URL,
            json=payload,
            headers=headers,
            timeout=DEEPSEEK_TIMEOUT,
        )
        elapsed = round(time.time() - start, 2)
        if not resp.ok:
            msg = f"HTTP {resp.status_code} 耗时={elapsed}s"
            logger.warning("%sDeepSeek 证据生成请求失败: status=%s body=%s 耗时=%ss", log_prefix, resp.status_code, resp.text[:300], elapsed)
            print(f"[DeepSeek] 未使用：请求失败 {msg}")
            return None

        data = resp.json()
        choices = data.get("choices") or []
        if not choices:
            logger.warning("%sDeepSeek 返回无 choices 耗时=%ss", log_prefix, elapsed)
            print(f"[DeepSeek] 未使用：返回无 choices，耗时={elapsed}s")
            return None

        content = (choices[0].get("message") or {}).get("content")
        if not content:
            logger.warning("%sDeepSeek 返回 content 为空 耗时=%ss", log_prefix, elapsed)
            print(f"[DeepSeek] 未使用：返回 content 为空，耗时={elapsed}s")
            return None

        raw = _extract_json_from_content(content)
        if not raw or not isinstance(raw, dict):
            logger.warning("%sDeepSeek 返回无法解析为 JSON 耗时=%ss content_prefix=%s", log_prefix, elapsed, content[:200])
            print(f"[DeepSeek] 未使用：返回无法解析为 JSON，耗时={elapsed}s")
            return None

        result = _ensure_six_dimensions(raw, duration_sec)
        total = sum(len(result[k]) for k in result)
        logger.info("%sDeepSeek 证据生成成功 共 %s 条证据 耗时=%ss", log_prefix, total, elapsed)
        print(f"[DeepSeek] 已使用：请求成功，共 {total} 条证据，耗时 {elapsed}s")
        return result

    except requests.exceptions.Timeout:
        logger.warning("%sDeepSeek 请求超时（%ss）", log_prefix, DEEPSEEK_TIMEOUT)
        print(f"[DeepSeek] 未使用：请求超时（{DEEPSEEK_TIMEOUT}s）")
        return None
    except requests.exceptions.RequestException as e:
        logger.warning("%sDeepSeek 请求异常: %s", log_prefix, e)
        print(f"[DeepSeek] 未使用：请求异常 {e}")
        return None
    except Exception as e:
        logger.exception("%sDeepSeek 证据生成异常: %s", log_prefix, e)
        print(f"[DeepSeek] 未使用：异常 {e}")
        return None
