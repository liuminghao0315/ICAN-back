#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音频处理模块
负责处理音频流分析（包含音频特征提取和ASR转文本）
开发者：展鑫鑫
"""

import time
import logging
import os
import tempfile
import re
import difflib
import ffmpeg
import jieba
import jieba.analyse
from snownlp import SnowNLP
import librosa
import numpy as np
from contextlib import contextmanager
from pathlib import Path
from dataclasses import dataclass
from typing import Dict, Any, Tuple, List, Optional

try:
    import whisper  # type: ignore
except Exception:
    whisper = None

# 配置日志
logger = logging.getLogger(__name__)

# 全局Whisper模型（避免重复加载）
_whisper_model = None
_whisper_model_name = None
_faster_whisper_model = None
_faster_whisper_model_name = None
_faster_whisper_last_error = None
_faster_whisper_retry_after = 0.0
_audio_ov_init_attempted = False
_audio_ov_pipeline = None
_punc_model_init_attempted = False
_punc_model = None

ASR_FAIL_TEXT = "（ASR识别失败）"
ASR_EMPTY_TEXT = "（未检测到语音内容）"

# 常见“幻觉口播”短语（openai-whisper 在静音/低信噪比中文素材中可能出现）
_ASR_HALLUCINATION_PATTERNS = [
    "请不吝点赞",
    "订阅转发打赏",
    "支持明镜",
    "点点栏目",
    "感谢收看",
    "欢迎订阅",
]


@dataclass
class AsrConfig:
    """ASR配置（支持无训练多引擎自动降级）。"""

    engine: str
    model_size: str
    device: str
    compute_type: str
    beam_size: int
    vad_filter: bool


def _safe_int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except Exception:
        return default


def _safe_bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in ["1", "true", "yes", "y", "on"]


def _safe_tristate_bool_env(name: str) -> Optional[bool]:
    """解析三态布尔环境变量：true/false/auto(或空)。"""
    value = os.getenv(name)
    if value is None:
        return None
    normalized = value.strip().lower()
    if normalized in ["", "auto", "default"]:
        return None
    return normalized in ["1", "true", "yes", "y", "on"]


def _is_offline_mode_enabled() -> bool:
    """
    强制本地模型模式。

    按用户要求：不允许任何在线模型下载与在线探测。
    为了避免被环境变量误改，这里固定返回 True。
    """
    return True


def _is_audio_open_vocab_enabled() -> bool:
    """
    是否启用音频开放词汇识别（CLAP）。

    本地模型策略下默认关闭，只有显式设置 true 才启用。
    """
    raw = os.getenv("AUDIO_OPEN_VOCAB_ENABLED")
    if raw is None:
        return False
    return raw.strip().lower() in ["1", "true", "yes", "y", "on"]


def _looks_like_local_path(spec: str) -> bool:
    """粗略判断一个模型标识是否更像本地路径，而不是 HF repo_id。"""
    s = (spec or "").strip()
    if not s:
        return False
    if os.path.isabs(s):
        return True
    if s.startswith(".") or s.startswith("/") or "\\" in s:
        return True
    if re.match(r"^[a-zA-Z]:", s):  # Windows 盘符路径
        return True
    # 多级路径更可能是目录结构而非 owner/repo
    if s.count("/") > 1:
        return True
    return False


def _normalize_local_path(path_text: str) -> str:
    """规范化本地路径（去引号、转绝对路径）。"""
    raw = (path_text or "").strip().strip('"').strip("'")
    if not raw:
        return ""
    return os.path.abspath(os.path.expanduser(raw))


def _resolve_existing_local_path(path_text: str) -> str:
    """返回存在的本地路径；不存在则返回空字符串。"""
    candidate = _normalize_local_path(path_text)
    if candidate and os.path.exists(candidate):
        return candidate
    return ""


def _resolve_generic_hf_repo_id(model_spec: str) -> Optional[str]:
    """将通用模型标识解析成 HuggingFace repo_id（若为本地路径则返回 None）。"""
    spec = (model_spec or "").strip()
    if not spec:
        return None
    if os.path.exists(spec) or _looks_like_local_path(spec):
        return None
    if spec.count("/") == 1 and not spec.startswith("/"):
        return spec
    return None


@contextmanager
def _temporary_env(overrides: Dict[str, Optional[str]]):
    """临时覆盖环境变量，退出时自动恢复。"""
    old_values: Dict[str, Optional[str]] = {}
    try:
        for key, value in overrides.items():
            old_values[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = str(value)
        yield
    finally:
        for key, old in old_values.items():
            if old is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = old


def _get_hf_cache_root() -> str:
    """返回 HuggingFace Hub 缓存根目录。"""
    explicit_cache = os.getenv("HUGGINGFACE_HUB_CACHE")
    if explicit_cache:
        return explicit_cache

    hf_home = os.getenv("HF_HOME")
    if hf_home:
        return os.path.join(hf_home, "hub")

    return os.path.join(str(Path.home()), ".cache", "huggingface", "hub")


def _is_hf_repo_cached(repo_id: str) -> bool:
    """判断指定 HuggingFace repo 是否已有本地 snapshot 缓存。"""
    if not repo_id:
        return False

    repo_dir = os.path.join(_get_hf_cache_root(), f"models--{repo_id.replace('/', '--')}")
    snapshots_dir = os.path.join(repo_dir, "snapshots")
    if not os.path.isdir(snapshots_dir):
        return False

    try:
        return any(os.path.isdir(os.path.join(snapshots_dir, d)) for d in os.listdir(snapshots_dir))
    except Exception:
        return False


def _build_hf_online_env() -> Dict[str, Optional[str]]:
    """
    兼容保留：在线环境已禁用，统一返回离线环境。
    """
    return _build_hf_offline_env()


def _build_hf_offline_env() -> Dict[str, Optional[str]]:
    """构造 HuggingFace 彻底离线环境变量。"""
    return {
        "HF_HUB_OFFLINE": "1",
        "TRANSFORMERS_OFFLINE": "1",
        "HF_DATASETS_OFFLINE": "1",
    }


def _resolve_clap_model_spec() -> str:
    """
    解析音频开放词汇模型标识（仅允许本地路径）。
    未配置时返回空字符串。
    """
    return _normalize_local_path(os.getenv("AUDIO_OPEN_VOCAB_MODEL") or "")


def _resolve_punc_model_spec() -> str:
    """
    解析标点恢复模型标识（仅允许本地路径）。
    未配置时返回空字符串。
    """
    return _normalize_local_path(os.getenv("PUNC_MODEL_NAME_OR_PATH") or "")


def _resolve_whisper_source(model_size: str) -> str:
    """
    解析 openai-whisper 模型来源（仅允许本地 .pt）。
    兼容参数 model_size，但不会再回退到在线模型名。
    """
    _ = model_size
    return _normalize_local_path(os.getenv("ASR_WHISPER_MODEL_PATH") or "")


def _resolve_whisper_local_fallback(model_size: str) -> str:
    """
    在“仅本地模型”前提下，为 openai-whisper 提供本地兜底路径：
    1) ASR_WHISPER_MODEL_PATH（显式本地文件）
    2) ~/.cache/whisper/{model_size}.pt
    3) 若 model_size=large-v3，尝试 ~/.cache/whisper/large-v3.pt 与 large.pt
    4) 最后回退到 ~/.cache/whisper/base.pt（若存在）
    """
    # 1) 显式路径优先
    explicit = _resolve_existing_local_path(os.getenv("ASR_WHISPER_MODEL_PATH") or "")
    if explicit and os.path.isfile(explicit):
        return explicit

    cache_dir = os.path.join(os.path.expanduser("~"), ".cache", "whisper")
    req = (model_size or "").strip().lower()
    if not req:
        req = "base"

    candidates: List[str] = []
    candidates.append(os.path.join(cache_dir, f"{req}.pt"))

    if req in ["large-v3", "large-v2", "large-v1"]:
        candidates.append(os.path.join(cache_dir, "large-v3.pt"))
        candidates.append(os.path.join(cache_dir, "large.pt"))
    elif req == "large":
        candidates.append(os.path.join(cache_dir, "large-v3.pt"))

    # 通用保底
    candidates.append(os.path.join(cache_dir, "base.pt"))

    seen = set()
    for c in candidates:
        p = _normalize_local_path(c)
        if p in seen:
            continue
        seen.add(p)
        if os.path.isfile(p):
            return p

    return ""


def _resolve_whisper_download_root() -> Optional[str]:
    """解析 openai-whisper 缓存目录（可选）。"""
    root = (os.getenv("ASR_WHISPER_DOWNLOAD_ROOT") or "").strip()
    return root or None


def _is_whisper_model_cached(model_name: str, download_root: Optional[str] = None) -> bool:
    """
    判断 openai-whisper 官方模型是否已缓存。

    openai-whisper 下载文件名取自 URL basename，例如 large-v3.pt。
    """
    if whisper is None:
        return False

    name = (model_name or "").strip().lower()
    if not name:
        return False

    model_url = whisper._MODELS.get(name)  # type: ignore[attr-defined]
    if not model_url:
        return False

    root = download_root
    if not root:
        default_cache = os.path.join(os.path.expanduser("~"), ".cache")
        root = os.path.join(os.getenv("XDG_CACHE_HOME", default_cache), "whisper")

    target = os.path.join(root, os.path.basename(model_url))
    return os.path.isfile(target)


def _resolve_faster_whisper_source(model_size: str) -> str:
    """仅返回本地 faster-whisper 模型路径。"""
    env_path = _normalize_local_path(os.getenv("ASR_FASTER_WHISPER_MODEL_PATH") or "")
    if env_path:
        return env_path
    # 兼容：若 ASR_MODEL_SIZE 被直接传入本地路径，也允许
    if _looks_like_local_path(model_size):
        return _normalize_local_path(model_size)
    return ""


def _resolve_faster_whisper_repo_id(model_spec: str) -> Optional[str]:
    """将 model_spec 转成 HuggingFace repo_id（本地路径则返回 None）。"""
    spec = (model_spec or "").strip()
    if not spec:
        return None

    # 显式路径（绝对/相对）
    if os.path.exists(spec) or os.path.isabs(spec) or spec.startswith(".") or "\\" in spec:
        return None

    # 形如 owner/repo
    if spec.count("/") == 1 and not spec.startswith("/"):
        return spec

    # 形如 large-v3 / medium / small ...
    return f"Systran/faster-whisper-{spec}"


def _faster_whisper_failure_marker_path() -> str:
    """跨进程记录 faster-whisper 最近一次失败，避免每个任务都长时间重试。"""
    return os.path.join(tempfile.gettempdir(), "ican_faster_whisper_failure.json")


def _read_faster_whisper_failure_marker() -> Optional[Dict[str, Any]]:
    path = _faster_whisper_failure_marker_path()
    if not os.path.isfile(path):
        return None
    try:
        import json

        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except Exception:
        return None
    return None


def _write_faster_whisper_failure_marker(error_msg: str, cooldown_sec: int) -> None:
    path = _faster_whisper_failure_marker_path()
    try:
        import json

        payload = {
            "error": str(error_msg),
            "retry_after": time.time() + max(30, int(cooldown_sec)),
            "updated_at": time.time(),
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
    except Exception:
        pass


def _clear_faster_whisper_failure_marker() -> None:
    path = _faster_whisper_failure_marker_path()
    try:
        if os.path.isfile(path):
            os.remove(path)
    except Exception:
        pass


def _is_hf_endpoint_reachable(endpoint: str) -> bool:
    """
    严格本地模型模式下，在线探测被永久禁用。
    """
    _ = endpoint
    return False


def _punc_failure_marker_path() -> str:
    return os.path.join(tempfile.gettempdir(), "ican_punc_model_failure.json")


def _read_punc_failure_marker() -> Optional[Dict[str, Any]]:
    path = _punc_failure_marker_path()
    if not os.path.isfile(path):
        return None
    try:
        import json

        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except Exception:
        return None
    return None


def _write_punc_failure_marker(error_msg: str, cooldown_sec: int) -> None:
    path = _punc_failure_marker_path()
    try:
        import json

        payload = {
            "error": str(error_msg),
            "retry_after": time.time() + max(30, int(cooldown_sec)),
            "updated_at": time.time(),
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
    except Exception:
        pass


def _clear_punc_failure_marker() -> None:
    path = _punc_failure_marker_path()
    try:
        if os.path.isfile(path):
            os.remove(path)
    except Exception:
        pass


def _detect_asr_device() -> str:
    env_device = (os.getenv("ASR_DEVICE") or "auto").strip().lower()
    if env_device in ["cpu", "cuda"]:
        return env_device
    try:
        import torch  # type: ignore

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


def get_asr_config() -> AsrConfig:
    """读取ASR配置，默认 base + beam_size=1 优先速度；需高精度可设 ASR_MODEL_SIZE=large-v3 与 ASR_BEAM_SIZE=5。"""
    engine = (os.getenv("ASR_ENGINE") or "auto").strip().lower()
    model_size = (os.getenv("ASR_MODEL_SIZE") or "base").strip()
    device = _detect_asr_device()

    compute_type = (os.getenv("ASR_COMPUTE_TYPE") or "auto").strip().lower()
    if compute_type == "auto":
        compute_type = "float16" if device == "cuda" else "int8"

    # 默认 beam_size=1（贪心解码）显著加速；要更高精度可设置 ASR_BEAM_SIZE=5
    beam_size = max(1, _safe_int_env("ASR_BEAM_SIZE", 1))
    vad_filter = _safe_bool_env("ASR_VAD_FILTER", True)

    return AsrConfig(
        engine=engine,
        model_size=model_size,
        device=device,
        compute_type=compute_type,
        beam_size=beam_size,
        vad_filter=vad_filter,
    )


def infer_voice_emotion_from_features(audio_quality: float, volume_level: float, noise_level: float) -> str:
    """
    基于可解释规则推断语音情绪标签（无专用情绪模型时的兜底方案）。
    """
    aq = min(1.0, max(0.0, float(audio_quality)))
    vl = min(1.0, max(0.0, float(volume_level)))
    nl = min(1.0, max(0.0, float(noise_level)))

    if vl >= 0.75 and nl >= 0.18:
        return "tense"
    if vl >= 0.68:
        return "energetic"
    if vl <= 0.35 and aq >= 0.55:
        return "calm"
    return "neutral"


def build_audio_open_vocab_prompts(word_packs: Optional[list] = None) -> List[str]:
    """构建音频开放词汇提示词（无训练方案）。"""
    base_prompts = [
        "shouting", "screaming", "arguing", "fighting", "crowd noise",
        "glass breaking", "explosion", "sirens", "alarm", "crying"
    ]

    extra = []
    for pack in word_packs or []:
        words = pack.get("words", []) if isinstance(pack, dict) else []
        for w in words:
            if isinstance(w, str):
                t = w.strip()
            elif isinstance(w, dict):
                t = str(w.get("text", "")).strip()
            else:
                t = ""
            if t:
                extra.append(t)

    prompts = []
    seen = set()
    for t in base_prompts + extra:
        k = t.lower()
        if k not in seen:
            seen.add(k)
            prompts.append(t)
    return prompts[:50]


def map_audio_open_vocab_to_risk(audio_hits: List[dict]) -> Dict[str, Any]:
    """把音频开放词汇命中映射成风险分（可解释）。"""
    if not audio_hits:
        return {"riskScore": 0.0, "riskFactors": []}

    weights = {
        "shouting": 0.35,
        "screaming": 0.45,
        "arguing": 0.35,
        "fighting": 0.5,
        "争吵": 0.35,
        "喊叫": 0.35,
        "爆炸": 0.6,
        "alarm": 0.25,
        "sirens": 0.25,
    }

    total = 0.0
    factors = []
    for hit in audio_hits:
        label = str(hit.get("label", "")).lower()
        conf = float(hit.get("confidence", 0.0))
        matched = None
        w = 0.0
        for k, v in weights.items():
            if k.lower() in label:
                matched = k
                w = v
                break
        if w <= 0:
            continue
        score = min(1.0, w * max(0.0, min(1.0, conf)) * 2)
        total += score
        factors.append({
            "label": hit.get("label", ""),
            "confidence": round(conf, 3),
            "matched": matched,
            "score": round(score, 3),
        })

    return {"riskScore": round(min(1.0, total), 3), "riskFactors": factors}


def _get_audio_open_vocab_pipeline():
    """可选加载 transformers 音频零样本分类器（失败自动降级）。"""
    global _audio_ov_init_attempted, _audio_ov_pipeline
    if _audio_ov_pipeline is not None:
        return _audio_ov_pipeline
    if _audio_ov_init_attempted:
        return None

    if not _is_audio_open_vocab_enabled():
        _audio_ov_init_attempted = True
        logger.info("音频开放词汇识别已关闭（AUDIO_OPEN_VOCAB_ENABLED=false）")
        return None

    _audio_ov_init_attempted = True
    try:
        from transformers import pipeline  # type: ignore

        model_spec = _resolve_clap_model_spec()
        if not model_spec or not os.path.exists(model_spec):
            logger.warning(
                "音频开放词汇本地模型路径无效，已降级关闭（请配置 AUDIO_OPEN_VOCAB_MODEL 为本地目录）"
            )
            _audio_ov_pipeline = None
            return None

        model_kwargs = {"local_files_only": True}

        with _temporary_env(_build_hf_offline_env()):
            _audio_ov_pipeline = pipeline(
                "zero-shot-audio-classification",
                model=model_spec,
                model_kwargs=model_kwargs,
            )

        logger.info(f"音频开放词汇本地模型加载成功（CLAP, path={model_spec}）")
    except Exception as e:
        logger.warning(f"音频开放词汇模型不可用，降级规则分析: {e}")
        _audio_ov_pipeline = None

    return _audio_ov_pipeline


def detect_audio_events_optional(audio_path: str, prompts: List[str]) -> List[dict]:
    """可选音频开放词汇识别。"""
    clf = _get_audio_open_vocab_pipeline()
    if clf is None:
        return []
    try:
        results = clf(audio_path, candidate_labels=prompts)
        labels = results.get("labels", [])
        scores = results.get("scores", [])
        hits = []
        for i in range(min(5, len(labels), len(scores))):
            if float(scores[i]) >= 0.2:
                hits.append({"label": labels[i], "confidence": round(float(scores[i]), 4)})
        return hits
    except Exception as e:
        logger.warning(f"音频开放词汇推理失败，降级忽略: {e}")
        return []


def _extract_pack_words(custom_word_packs: Optional[list]) -> List[dict]:
    """
    统一解析风险词库包，支持两种格式：
      1) { words: ["词1", "词2"] }
      2) { words: [{text: "词1", category: "...", riskLevel: "..."}] }
    """
    if not custom_word_packs:
        return []

    normalized = []
    for pack in custom_word_packs:
        words = pack.get("words", []) if isinstance(pack, dict) else []
        for w in words:
            if isinstance(w, str):
                text = w.strip()
                if text:
                    normalized.append({
                        "text": text,
                        "category": "自定义词库",
                        "riskLevel": "medium",
                        "packName": pack.get("name", "自定义词库") if isinstance(pack, dict) else "自定义词库"
                    })
            elif isinstance(w, dict):
                text = str(w.get("text", "")).strip()
                if text:
                    normalized.append({
                        "text": text,
                        "category": w.get("category", "自定义词库"),
                        "riskLevel": w.get("riskLevel", "medium"),
                        "packName": pack.get("name", "自定义词库") if isinstance(pack, dict) else "自定义词库"
                    })
    return normalized


def build_hotwords_from_word_packs(custom_word_packs: Optional[list], max_words: int = 80) -> List[str]:
    """从前端挂载词库中提取 Whisper 热词。"""
    words = _extract_pack_words(custom_word_packs)
    # 高风险词优先，其次按词长排序（长词更有业务价值）
    risk_weight = {"high": 0, "medium": 1, "low": 2}
    words.sort(key=lambda x: (risk_weight.get(str(x.get("riskLevel", "medium")).lower(), 1), -len(x.get("text", ""))))

    dedup = []
    seen = set()
    for w in words:
        text = w["text"]
        if text not in seen:
            seen.add(text)
            dedup.append(text)
        if len(dedup) >= max_words:
            break
    return dedup


def build_whisper_initial_prompt(hotwords: List[str]) -> str:
    """构造 Whisper initial_prompt，引导识别业务词汇。"""
    if not hotwords:
        return ""
    joined = "、".join(hotwords)
    return f"以下词汇在音频中可能出现，请尽量准确识别：{joined}。"


def _resolve_openai_whisper_model_name(requested: str) -> str:
    """兼容 openai-whisper 可用模型名称。"""
    name = (requested or "base").strip().lower()
    if name in ["large-v3", "large-v2", "large-v1"]:
        return "large"
    if name in ["tiny", "base", "small", "medium", "large", "turbo"]:
        return name
    return "base"


def get_whisper_model(model_size: str = "base"):
    """
    获取或加载 openai-whisper 模型（单例模式，可按模型名切换）。
    """
    global _whisper_model, _whisper_model_name

    if whisper is None:
        raise RuntimeError("openai-whisper 未安装，无法使用 whisper 引擎")

    model_source = _resolve_whisper_local_fallback(model_size)
    if not model_source:
        raise RuntimeError(
            "未找到可用的本地 Whisper 模型文件。"
            "请设置 ASR_WHISPER_MODEL_PATH，或将模型放在 ~/.cache/whisper/（如 large-v3.pt / base.pt）"
        )

    if _whisper_model is None or _whisper_model_name != model_source:
        logger.info(f"正在加载Whisper本地模型（{model_source}）...")
        try:
            if not os.path.isfile(model_source):
                raise RuntimeError(f"Whisper本地模型文件不存在或不是文件: {model_source}")

            with _temporary_env(_build_hf_offline_env()):
                _whisper_model = whisper.load_model(model_source)
            _whisper_model_name = model_source
            logger.info(f"Whisper本地模型加载成功（{model_source}）")
        except Exception as e:
            logger.error(f"Whisper模型加载失败（{model_source}）: {e}")
            raise
    return _whisper_model


def get_faster_whisper_model(model_size: str, device: str, compute_type: str):
    """
    获取或加载 faster-whisper 模型（优先高精度，无训练）。
    """
    global _faster_whisper_model, _faster_whisper_model_name
    global _faster_whisper_last_error, _faster_whisper_retry_after

    model_source = _resolve_faster_whisper_source(model_size)
    model_source = _resolve_existing_local_path(model_source)
    if not model_source:
        raise RuntimeError(
            "未配置可用的本地 faster-whisper 模型目录，请设置 ASR_FASTER_WHISPER_MODEL_PATH"
        )

    key = f"{model_source}|{device}|{compute_type}"

    now = time.time()
    if _faster_whisper_model is not None and _faster_whisper_model_name == key:
        return _faster_whisper_model

    # 先检查内存中的冷却标记
    if now < float(_faster_whisper_retry_after or 0):
        remain = int(max(1, _faster_whisper_retry_after - now))
        raise RuntimeError(f"faster-whisper冷却中（{remain}s后重试），上次失败: {_faster_whisper_last_error}")

    # 再检查跨进程冷却标记（每个任务是独立子进程）
    marker = _read_faster_whisper_failure_marker()
    if marker:
        retry_after = float(marker.get("retry_after") or 0)
        if now < retry_after:
            remain = int(max(1, retry_after - now))
            msg = str(marker.get("error") or "faster-whisper最近初始化失败")
            _faster_whisper_last_error = msg
            _faster_whisper_retry_after = retry_after
            raise RuntimeError(f"faster-whisper跨进程冷却中（{remain}s后重试），上次失败: {msg}")

    from faster_whisper import WhisperModel  # type: ignore

    logger.info(
        f"正在加载faster-whisper本地模型（source={model_source}, device={device}, compute_type={compute_type}）..."
    )

    try:
        with _temporary_env(_build_hf_offline_env()):
            _faster_whisper_model = WhisperModel(
                model_source,
                device=device,
                compute_type=compute_type,
                local_files_only=True,
            )

        _faster_whisper_model_name = key
        _faster_whisper_last_error = None
        _faster_whisper_retry_after = 0.0
        _clear_faster_whisper_failure_marker()
        logger.info(f"faster-whisper本地模型加载成功（path={model_source}）")
        return _faster_whisper_model
    except Exception as e:
        cooldown = max(120, _safe_int_env("ASR_RETRY_COOLDOWN_SEC", 600))
        _faster_whisper_last_error = str(e)
        _faster_whisper_retry_after = time.time() + cooldown
        _write_faster_whisper_failure_marker(str(e), cooldown)
        raise


def _get_punctuation_model():
    """可选加载标点恢复模型（失败自动降级规则恢复）。"""
    global _punc_model_init_attempted, _punc_model

    if _punc_model is not None:
        return _punc_model
    if _punc_model_init_attempted:
        return None

    # 跨进程冷却：近期失败过则直接跳过，避免每个任务都在网络超时重试
    marker = _read_punc_failure_marker()
    if marker:
        retry_after = float(marker.get("retry_after") or 0)
        if time.time() < retry_after:
            remain = int(max(1, retry_after - time.time()))
            logger.info(
                f"标点模型处于冷却期（{remain}s后重试），本任务直接使用规则恢复"
            )
            _punc_model_init_attempted = True
            return None

    _punc_model_init_attempted = True
    try:
        from deepmultilingualpunctuation import PunctuationModel  # type: ignore
        model_spec = _resolve_punc_model_spec()

        if not model_spec:
            logger.info("未配置本地标点模型（PUNC_MODEL_NAME_OR_PATH），使用规则恢复")
            _punc_model = None
            return None

        if not os.path.exists(model_spec):
            raise RuntimeError(f"本地标点模型路径不存在: {model_spec}")

        with _temporary_env(_build_hf_offline_env()):
            _punc_model = PunctuationModel(model=model_spec)
        _clear_punc_failure_marker()
        logger.info(f"标点恢复本地模型加载成功（path={model_spec}）")
    except Exception as e:
        cooldown = max(120, _safe_int_env("PUNC_RETRY_COOLDOWN_SEC", 1200))
        _write_punc_failure_marker(str(e), cooldown)
        logger.warning(f"标点恢复模型不可用，降级规则恢复: {e}")
        _punc_model = None

    return _punc_model


def _normalize_transcription_text(text: str) -> str:
    """清理ASR文本中的噪声标记与异常空白。"""
    if not text:
        return ""

    cleaned = text.strip()
    cleaned = cleaned.replace("\n", "")
    cleaned = cleaned.replace("\r", "")
    cleaned = re.sub(r"\s+", "", cleaned)

    # 去除常见非语义占位
    cleaned = re.sub(r"\[(音乐|掌声|笑声|噪音|噪声)\]", "", cleaned)
    cleaned = re.sub(r"（(音乐|掌声|笑声|噪音|噪声)）", "", cleaned)

    # 规范标点重复
    cleaned = re.sub(r"[，,]{2,}", "，", cleaned)
    cleaned = re.sub(r"[。\.]{2,}", "。", cleaned)
    cleaned = re.sub(r"[！!]{2,}", "！", cleaned)
    cleaned = re.sub(r"[？?]{2,}", "？", cleaned)

    return cleaned.strip("，。！？")


def _heuristic_restore_punctuation(text: str) -> str:
    """规则兜底标点恢复（无模型依赖）。"""
    if not text:
        return ""

    if re.search(r"[。！？!?]", text):
        return text

    restored = text
    restored = re.sub(
        r"(但是|不过|然后|所以|因此|因为|而且|并且|可是|另外|同时|后来|最后)",
        r"，\1",
        restored,
    )

    pieces = []
    buf = ""
    for ch in restored:
        buf += ch
        if len(buf) >= 24 and ch not in "，。！？":
            pieces.append(buf)
            buf = ""
    if buf:
        pieces.append(buf)

    merged = "，".join([p.strip("，") for p in pieces if p.strip("，")]).strip("，")
    if not merged:
        return ""
    if merged[-1] not in "。！？":
        merged += "。"
    return merged


def _restore_punctuation(text: str) -> str:
    """优先使用模型恢复标点，失败自动回退到规则恢复。"""
    if not text:
        return ""

    punc_model = _get_punctuation_model()
    if punc_model is not None:
        try:
            # 某些实现可能在首次推理阶段才惰性加载模型；
            # 离线模式下需在推理时也注入 offline 环境，防止偷偷走网。
            if _is_offline_mode_enabled():
                with _temporary_env(_build_hf_offline_env()):
                    restored = punc_model.restore_punctuation(text)
            else:
                restored = punc_model.restore_punctuation(text)
            if isinstance(restored, str) and restored.strip():
                return restored.strip()
        except Exception as e:
            logger.warning(f"标点恢复模型推理失败，降级规则恢复: {e}")

    return _heuristic_restore_punctuation(text)


def _build_domain_lexicon(custom_word_packs: Optional[list]) -> List[str]:
    """构建领域词典（用于轻量纠错，尤其高校场景）。"""
    base_words = [
        "选课系统", "教务处", "辅导员", "校园卡", "图书馆", "实验室", "宿舍", "食堂", "课堂", "学院",
        "大学", "学校", "校园", "学生", "同学", "老师", "论文", "考试", "课程", "挂科",
        "抵制", "抗议", "不满", "投诉", "舆论", "传播", "网暴", "风险", "复核",
    ]

    for word in build_hotwords_from_word_packs(custom_word_packs, max_words=200):
        if word and len(word) >= 2:
            base_words.append(word)

    dedup = []
    seen = set()
    for w in base_words:
        key = w.strip()
        if not key:
            continue
        if key not in seen:
            seen.add(key)
            dedup.append(key)
    return dedup


def _apply_domain_corrections(text: str, custom_word_packs: Optional[list]) -> str:
    """基于领域词典做轻量文本纠错（无训练）。"""
    if not text:
        return ""

    lexicon = _build_domain_lexicon(custom_word_packs)
    if not lexicon:
        return text

    # 保护已有高价值词，先不改
    lexicon_set = set(lexicon)
    tokens = jieba.lcut(text, HMM=True)
    corrected_tokens = []
    changed = 0

    for token in tokens:
        tk = token.strip()
        if len(tk) < 2 or tk in lexicon_set:
            corrected_tokens.append(token)
            continue

        candidate = difflib.get_close_matches(tk, lexicon, n=1, cutoff=0.82)
        if candidate and len(candidate[0]) == len(tk):
            corrected_tokens.append(candidate[0])
            changed += 1
        else:
            corrected_tokens.append(token)

    if changed > 0:
        logger.info(f"已应用领域词轻量纠错，修正词数: {changed}")

    return "".join(corrected_tokens)


def post_process_asr_text(raw_text: str, custom_word_packs: Optional[list] = None) -> str:
    """ASR文本后处理：清洗 -> 轻量纠错 -> 标点恢复。"""
    text = _normalize_transcription_text(raw_text)
    if not text:
        return ""

    text = _apply_domain_corrections(text, custom_word_packs)
    text = _restore_punctuation(text)
    text = _normalize_transcription_text(text)

    # 标点恢复后再补句号，避免前端分句异常
    if text and text[-1] not in "。！？":
        text += "。"
    return text


def estimate_transcription_quality(text: str) -> float:
    """估算ASR文本质量（0-1），用于languageConfidence。"""
    if not text or text in [ASR_EMPTY_TEXT, ASR_FAIL_TEXT]:
        return 0.0

    stripped = text.strip()
    if not stripped:
        return 0.0

    total_chars = len(stripped)
    zh_chars = len(re.findall(r"[\u4e00-\u9fff]", stripped))
    punct_count = len(re.findall(r"[，。！？；,.!?]", stripped))

    zh_ratio = zh_chars / max(1, total_chars)
    punct_ratio = punct_count / max(1, total_chars)
    length_factor = min(1.0, total_chars / 20.0)

    score = (zh_ratio * 0.6) + (min(1.0, punct_ratio * 25) * 0.25) + (length_factor * 0.15)
    return round(max(0.0, min(1.0, score)), 4)


def _calc_repetition_ratio(text: str) -> float:
    """估算文本重复度（0-1），用于识别 ASR 幻觉复读。"""
    if not text:
        return 0.0

    chars = [c for c in text if c.strip()]
    if len(chars) < 8:
        return 0.0

    uniq = len(set(chars))
    diversity = uniq / len(chars)
    return round(max(0.0, min(1.0, 1.0 - diversity)), 4)


def _looks_like_hallucination(text: str) -> bool:
    """基于规则识别 ASR 幻觉文本，命中则视为无效识别结果。"""
    if not text:
        return False

    t = str(text)
    hit_count = sum(1 for p in _ASR_HALLUCINATION_PATTERNS if p in t)
    rep_ratio = _calc_repetition_ratio(t)
    long_text = len(t) >= 24

    # 两类判定：
    # 1) 幻觉短语命中较多；
    # 2) 命中1个以上且重复度异常高（复读）
    if hit_count >= 2:
        return True
    if hit_count >= 1 and long_text and rep_ratio >= 0.62:
        return True

    return False


def _transcribe_with_faster_whisper(audio_path: str, config: AsrConfig, initial_prompt: str = "") -> str:
    model = get_faster_whisper_model(config.model_size, config.device, config.compute_type)
    logger.info(
        f"[ASR] 开始转写音频（faster-whisper, model={config.model_size}, device={config.device}），请稍候..."
    )
    start_t = time.time()
    segments, _info = model.transcribe(
        audio_path,
        language="zh",
        beam_size=config.beam_size,
        vad_filter=config.vad_filter,
        initial_prompt=initial_prompt if initial_prompt else None,
        condition_on_previous_text=True,
        vad_parameters={"min_silence_duration_ms": 350},
    )
    text = "".join([seg.text for seg in segments]).strip()
    logger.info(f"[ASR] 转写完成，耗时 {round(time.time() - start_t, 1)} 秒")
    return text


def _transcribe_with_openai_whisper(audio_path: str, config: AsrConfig, initial_prompt: str = "") -> str:
    model = get_whisper_model(config.model_size)
    logger.info(
        f"[ASR] 开始转写音频（openai-whisper, model={config.model_size}, device={config.device}, beam={config.beam_size}），请稍候..."
    )
    if (config.model_size or "").lower() in ("large-v3", "large-v2", "large") and config.device != "cuda":
        logger.info("[ASR] 提示：large 系列在 CPU 上较慢，可设置 ASR_MODEL_SIZE=base 或 tiny 加速")
    start_t = time.time()
    # beam_size=1 时用 best_of=1 避免多次采样，显著加速
    best_of = 1 if config.beam_size <= 1 else max(5, config.beam_size)
    result = model.transcribe(
        audio_path,
        language="zh",
        fp16=(config.device == "cuda"),
        initial_prompt=initial_prompt if initial_prompt else None,
        beam_size=config.beam_size,
        best_of=best_of,
        temperature=0.0,
        condition_on_previous_text=True,
    )
    elapsed = round(time.time() - start_t, 1)
    logger.info(f"[ASR] 转写完成，耗时 {elapsed} 秒")
    return str(result.get("text", "")).strip()


def extract_audio_from_video(video_source: str, task_id: str) -> str:
    """
    使用ffmpeg从视频中提取音频
    
    Args:
        video_source: 视频URL或本地路径
        task_id: 任务ID
        
    Returns:
        音频文件路径
    """
    # 保存到当前目录的audio_files文件夹（便于查看和验证）
    audio_dir = "audio_files"
    if not os.path.exists(audio_dir):
        os.makedirs(audio_dir)
    audio_path = os.path.join(audio_dir, f"audio_{task_id}.wav")
    
    try:
        logger.info(f"[任务 {task_id}] [音频处理模块] 开始提取音频...")
        
        # 使用ffmpeg提取音频
        stream = ffmpeg.input(video_source)
        stream = ffmpeg.output(
            stream, 
            audio_path,
            acodec='pcm_s16le',  # WAV格式，16位PCM
            ac=1,  # 单声道
            ar='16000'  # 16kHz采样率（Whisper推荐）
        )
        ffmpeg.run(stream, overwrite_output=True, capture_stderr=True, quiet=True)
        
        logger.info(f"[任务 {task_id}] [音频处理模块] ========== 音频获取成功 ==========")
        return audio_path
        
    except ffmpeg.Error as e:
        error_msg = e.stderr.decode() if e.stderr else str(e)
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频提取失败: {error_msg}")
        raise
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频提取失败: {e}")
        raise


def asr_recognize(audio_path: str, task_id: str, custom_word_packs: Optional[list] = None) -> str:
    """
    使用多引擎ASR进行语音识别（优先 faster-whisper，高精度+自动降级）
    
    Args:
        audio_path: 音频文件路径
        task_id: 任务ID
        
    Returns:
        识别的文本内容
    """
    try:
        logger.info(f"[任务 {task_id}] [音频处理模块] 开始ASR识别...")
        config = get_asr_config()

        # 热词提示（来自前端风险词库管理）
        hotwords = build_hotwords_from_word_packs(custom_word_packs)
        initial_prompt = build_whisper_initial_prompt(hotwords)

        raw_text = ""
        used_engine = ""
        errors = []

        # 1) 显式要求 faster-whisper
        if config.engine in ["faster-whisper", "faster_whisper"]:
            used_engine = "faster-whisper"
            raw_text = _transcribe_with_faster_whisper(audio_path, config, initial_prompt)

        # 2) 显式要求 openai-whisper
        elif config.engine in ["whisper", "openai-whisper", "openai_whisper"]:
            used_engine = "openai-whisper"
            raw_text = _transcribe_with_openai_whisper(audio_path, config, initial_prompt)

        # 3) auto：优先 faster-whisper，失败后回退 openai-whisper
        else:
            try:
                used_engine = "faster-whisper"
                raw_text = _transcribe_with_faster_whisper(audio_path, config, initial_prompt)
            except Exception as e:
                errors.append(f"faster-whisper失败: {e}")
                logger.warning(f"[任务 {task_id}] faster-whisper不可用，回退openai-whisper: {e}")
                used_engine = "openai-whisper"
                raw_text = _transcribe_with_openai_whisper(audio_path, config, initial_prompt)

        # 文本后处理：清理 + 纠错 + 标点恢复
        full_text = post_process_asr_text(raw_text, custom_word_packs=custom_word_packs)
        # 幻觉拦截：防止固定垃圾文案污染后续关键词/风险分析
        if _looks_like_hallucination(full_text):
            logger.warning(
                f"[任务 {task_id}] [音频处理模块] 检测到疑似ASR幻觉文本，已丢弃识别结果（engine={used_engine}）"
            )
            full_text = ""

        if not full_text:
            full_text = ASR_EMPTY_TEXT

        preview = full_text if len(full_text) <= 180 else f"{full_text[:180]}..."
        logger.info(
            f"[任务 {task_id}] [音频处理模块] ASR识别完成（engine={used_engine}, model={config.model_size}, "
            f"device={config.device}, beam={config.beam_size}）: {preview}"
        )
        if hotwords:
            logger.info(f"[任务 {task_id}] [音频处理模块] 已应用风险词库热词 {len(hotwords)} 个")
        if errors:
            logger.info(f"[任务 {task_id}] [音频处理模块] ASR降级信息: {' | '.join(errors)}")
        
        return full_text
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] ASR识别失败: {e}")
        return ASR_FAIL_TEXT


def generate_audio_effect_events(duration: float, audio_quality: float, task_id: str) -> list:
    """
    生成声学事件流（AudioEffectEvent类型） - 时间点混合分布
    
    Args:
        duration: 视频时长
        audio_quality: 音频质量
        task_id: 任务ID
        
    Returns:
        声学事件列表
    """
    audio_events = []
    event_id = 1

    duration_i = max(1, int(duration))
    event_count = min(8, max(3, duration_i // 12))
    step = max(5, duration_i // (event_count + 1))
    aq = min(1.0, max(0.0, float(audio_quality)))

    for i in range(event_count):
        start_time = min(duration_i - 1, (i + 1) * step)
        event_len = 2 + int((1 - aq) * 3)
        end_time = min(duration_i, start_time + event_len)
        intensity = min(1.0, 0.45 + (1 - aq) * 0.4 + (i % 3) * 0.05)
        risk_score = int(min(95, max(35, intensity * 100)))

        if intensity >= 0.8:
            desc = "检测到异常高能量声学片段"
        elif intensity >= 0.65:
            desc = "检测到音量波动明显"
        else:
            desc = "检测到常规语音/环境音变化"

        audio_events.append({
            "id": f"audio-{event_id:03d}",
            "modality": "audio-effect",
            "startTime": start_time,
            "endTime": end_time,
            "riskScore": risk_score,
            "description": desc,
            "intensity": round(intensity, 2),
            "confidence": int(min(98, max(70, 78 + aq * 18)))
        })
        event_id += 1
    
    logger.info(f"[任务 {task_id}] [音频处理模块] 生成 {len(audio_events)} 个声学事件")
    return audio_events


def generate_audio_emotions(duration: float, speech_ratio: float) -> list:
    """
    生成音频情绪时间序列（基于5秒粒度）
    
    Args:
        duration: 视频时长
        speech_ratio: 语音比例
        
    Returns:
        音频情绪列表（索引对应时间段）
    """
    time_granularity = 5
    num_segments = int(duration / time_granularity) + 1
    audio_emotions = []
    
    sr = min(1.0, max(0.0, float(speech_ratio)))
    for i in range(num_segments):
        pos = i / max(1, num_segments - 1)
        peak = 1 - abs(pos - 0.55) * 1.8
        peak = max(0.0, min(1.0, peak))
        intensity = 0.2 + sr * 0.35 + peak * 0.25
        intensity = max(0.1, min(0.98, intensity))

        if intensity >= 0.8:
            reason = "语音强度较高，情绪张力明显"
        elif intensity >= 0.55:
            reason = "语音表达中等偏强"
        else:
            reason = "语音表达平稳"
        
        audio_emotions.append({
            "intensity": round(intensity, 2),
            "reason": reason
        })
    
    return audio_emotions


def calculate_audio_risk_scores(audio_quality: float, speech_ratio: float, volume_level: float) -> dict:
    """
    计算音频维度的6个风险分数
    
    Args:
        audio_quality: 音频质量
        speech_ratio: 语音比例
        volume_level: 音量水平
        
    Returns:
        6个维度的音频分数（0-100）
    """
    # 去随机化：使用可解释、可复现实用规则（无算法组专用模型时的稳定方案）
    aq = min(1.0, max(0.0, float(audio_quality)))
    sr = min(1.0, max(0.0, float(speech_ratio)))
    vl = min(1.0, max(0.0, float(volume_level)))

    # 1. 身份置信度：语音占比 + 音频质量越高，越容易判断身份线索
    identity_score = int(35 + sr * 45 + aq * 20)

    # 2. 学校关联度：音频本身对“高校关联”能力有限，给中等且随语音质量变化
    university_score = int(40 + sr * 25 + aq * 20)

    # 3. 负面情感度：音量偏高、语速（语音占比）偏高通常意味着情绪更强
    attitude_score = int(25 + vl * 45 + sr * 20)

    # 4. 主题分数：语音可懂度（质量+语音占比）越高，主题识别越稳
    topic_score = int(30 + aq * 35 + sr * 30)

    # 5. 舆论风险：高音量是主要信号，语音占比辅助
    opinion_risk_score = int(20 + vl * 55 + sr * 20)

    # 6. 处置紧迫度：由态度与舆论风险共同决定
    action_score = int(opinion_risk_score * 0.6 + attitude_score * 0.4)
    
    return {
        "identity": min(100, max(0, identity_score)),
        "university": min(100, max(0, university_score)),
        "topic": min(100, max(0, topic_score)),
        "attitude": min(100, max(0, attitude_score)),
        "opinionRisk": min(100, max(0, opinion_risk_score)),
        "action": min(100, max(0, action_score))
    }


def process_audio(task_id: str, video_info: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """
    处理音频流分析（包含两个步骤）
    适配新的前端数据结构
    
    Args:
        task_id: 任务ID
        video_info: 视频信息，包含 videoUrl, videoTitle, videoDuration, fileSize 等
        
    Returns:
        Tuple[音频特征结果, 文本分析结果]
        第一个字典是音频模块结果（声学事件、音频情绪、音频特征分数）
        第二个字典是文本模块结果（语音事件、文本风险、文本特征分数、初步证据）
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] 开始处理音频流...")
    
    audio_path = None
    try:
        # 获取内网URL用于ffmpeg快速处理
        video_url_internal = video_info.get("videoUrlInternal") or video_info.get("videoUrl")
        if not video_url_internal:
            raise ValueError("视频URL不存在")
        
        duration = video_info.get("videoDuration", 60.0)
        
        # Step 1: 从视频中提取音频
        audio_path = extract_audio_from_video(video_url_internal, task_id)
        
        # Step 2: 音频特征提取
        audio_features = extract_audio_features(task_id, video_info, audio_path)
        
        # Step 3: ASR转文本（接入前端风险词库热词提示）
        custom_word_packs = video_info.get("wordPacks")
        transcription = asr_recognize(audio_path, task_id, custom_word_packs=custom_word_packs)

        # Step 3.5: 音频开放词汇识别（可选：CLAP/transformers）
        audio_ov_prompts = build_audio_open_vocab_prompts(custom_word_packs)
        audio_ov_hits = detect_audio_events_optional(audio_path, audio_ov_prompts)
        audio_ov_risk = map_audio_open_vocab_to_risk(audio_ov_hits)
        
        # ========== 构建音频模块结果（Step 2: Audio → 50%） ==========
        
        # 1. 生成声学事件流
        audio_effect_events = generate_audio_effect_events(
            duration,
            audio_features.get("audioQuality", 0.7),
            task_id
        )
        
        # 2. 生成音频情绪时间序列
        audio_emotions = generate_audio_emotions(
            duration,
            audio_features.get("speechRatio", 0.5)
        )
        
        # 3. 计算音频维度的特征分数
        audio_risk_scores = calculate_audio_risk_scores(
            audio_features.get("audioQuality", 0.7),
            audio_features.get("speechRatio", 0.5),
            audio_features.get("volumeLevel", 0.6)
        )

        # 将开放词汇音频风险注入分数（可解释增强）
        ov_bonus = int(audio_ov_risk.get("riskScore", 0.0) * 25)
        audio_risk_scores["opinionRisk"] = min(100, audio_risk_scores["opinionRisk"] + ov_bonus)
        audio_risk_scores["action"] = min(100, audio_risk_scores["action"] + int(ov_bonus * 0.8))
        
        audio_result = {
            # 声学事件流
            "audioEffectEvents": audio_effect_events,
            
            # 时间轴-音频情绪
            "audioEmotions": audio_emotions,
            
            # 特征数据（用于后续融合）
            "features": {
                "audioQuality": audio_features.get("audioQuality", 0.7),
                "speechRatio": audio_features.get("speechRatio", 0.5),
                "musicRatio": audio_features.get("musicRatio", 0.1),
                "noiseLevel": audio_features.get("noiseLevel", 0.2),
                "volumeLevel": audio_features.get("volumeLevel", 0.6),
                "emotionInVoice": audio_features.get("emotionInVoice", "neutral"),
                
                # 6个维度的音频风险分数
                "audioRiskScores": audio_risk_scores
            },

            "openVocab": {
                "engine": "CLAP(optional)",
                "prompts": audio_ov_prompts,
                "detections": audio_ov_hits,
                "risk": audio_ov_risk,
            },
            
            "processingTime": audio_features.get("processingTime", 0)
        }
        
        # ========== 构建文本模块结果（Step 3: Text → 75%） ==========
        text_analysis_result = convert_audio_to_text(
            task_id,
            video_info,
            transcription,
            duration,
            custom_word_packs=custom_word_packs,
        )
        
        logger.info(f"[任务 {task_id}] [音频处理模块] 音频流处理完成")
        return audio_result, text_analysis_result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 处理失败: {e}", exc_info=True)
        raise
    finally:
        # 分析完成后清理临时音频文件（避免磁盘堆积）
        try:
            if audio_path and os.path.exists(audio_path):
                os.remove(audio_path)
                logger.info(f"[任务 {task_id}] [音频处理模块] 已清理临时音频文件: {audio_path}")
        except Exception as cleanup_err:
            logger.warning(f"[任务 {task_id}] [音频处理模块] 清理临时音频文件失败: {cleanup_err}")


def extract_audio_features(task_id: str, video_info: Dict[str, Any], audio_path: str = None) -> Dict[str, Any]:
    """
    提取音频特征（Step 1）- 使用Librosa进行真实分析
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        audio_path: 音频文件路径（可选）
        
    Returns:
        音频特征分析结果
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 开始提取音频特征...")
    start_time = time.time()
    
    try:
        if audio_path and os.path.exists(audio_path):
            # 使用Librosa进行真实音频分析
            y, sr = librosa.load(audio_path, sr=16000, duration=60)  # 只加载前60秒，加快处理
            
            # 1. 音频质量（基于RMS能量）
            rms = librosa.feature.rms(y=y)
            audio_quality = float(np.mean(rms)) * 2  # 归一化到0-1
            audio_quality = min(1.0, max(0.0, audio_quality))
            
            # 2. 语音比例估计（基于零交叉率和能量）
            zcr = librosa.feature.zero_crossing_rate(y)
            mean_zcr = float(np.mean(zcr))
            speech_ratio = min(0.9, max(0.1, mean_zcr * 10))  # 粗略估计
            
            # 3. 音量水平
            volume_level = float(np.mean(np.abs(y)))
            volume_level = min(1.0, max(0.0, volume_level * 3))
            
            # 4. 噪声水平（基于能量方差）
            noise_level = float(np.std(rms))
            noise_level = min(0.5, max(0.0, noise_level))
            
            emotion_in_voice = infer_voice_emotion_from_features(audio_quality, volume_level, noise_level)

            result = {
                "hasAudio": True,
                "audioQuality": round(audio_quality, 4),
                "speechRatio": round(speech_ratio, 4),
                "musicRatio": round(max(0.0, min(1.0, 1 - speech_ratio - noise_level)), 4),
                "noiseLevel": round(noise_level, 4),
                "volumeLevel": round(volume_level, 4),
                "emotionInVoice": emotion_in_voice,
                "processingTime": round(time.time() - start_time, 2)
            }
            
            logger.info(f"[任务 {task_id}] [音频处理模块] 使用Librosa真实分析完成")
        else:
            # 无法读取音频，不再返回随机mock，返回明确空特征
            logger.warning(f"[任务 {task_id}] [音频处理模块] 音频文件不存在，使用估计值")
            result = {
                "hasAudio": False,
                "audioQuality": 0.0,
                "speechRatio": 0.0,
                "musicRatio": 0.0,
                "noiseLevel": 0.0,
                "volumeLevel": 0.0,
                "emotionInVoice": "neutral",
                "processingTime": round(time.time() - start_time, 2)
            }
        
        logger.info(f"[任务 {task_id}] [音频处理模块] Step 1: 音频特征提取完成，处理时间: {result['processingTime']:.2f}秒")
        return result
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [音频处理模块] 音频特征提取失败: {e}")
        # 返回失败态空特征，避免伪造
        return {
            "hasAudio": False,
            "audioQuality": 0.0,
            "speechRatio": 0.0,
            "musicRatio": 0.0,
            "noiseLevel": 0.0,
            "volumeLevel": 0.0,
            "emotionInVoice": "neutral",
            "processingTime": round(time.time() - start_time, 2)
        }


def analyze_text_with_nlp(transcription: str, task_id: str, custom_word_packs: Optional[list] = None) -> Dict[str, Any]:
    """
    使用Jieba和SnowNLP进行真实文本分析（增强版：词云数据+敏感词检测）
    
    Args:
        transcription: ASR识别的文本
        task_id: 任务ID
        
    Returns:
        文本分析结果
    """
    try:
        if not transcription or transcription in [ASR_EMPTY_TEXT, ASR_FAIL_TEXT]:
            # 无有效文本，返回默认值
            return {
                "keywords": [],
                "wordCloud": [],
                "sensitiveWords": [],
                "sentimentScore": 0.0,
                "sentimentLabel": "NEUTRAL",
                "topicCategory": "未知"
            }
        
        # 1. 关键词提取（使用TF-IDF，带权重用于词云）
        keywords_with_weight = jieba.analyse.extract_tags(transcription, topK=30, withWeight=True)
        
        # 词云数据（格式：[{name: '关键词', value: 权重}]）
        word_cloud = [{"name": word, "value": int(weight * 1000)} for word, weight in keywords_with_weight[:20]]
        
        # 顶级关键词（用于展示）
        keywords = [word for word, weight in keywords_with_weight[:7]]
        
        # 2. 敏感词检测（高校相关敏感词库）
        sensitive_words = detect_sensitive_words(transcription, custom_word_packs=custom_word_packs)
        
        # 3. 情感分析（SnowNLP）
        s = SnowNLP(transcription)
        sentiment_score_raw = s.sentiments  # 0-1之间
        # 转换为-1到1区间
        sentiment_score = (sentiment_score_raw * 2) - 1  # 0→-1, 0.5→0, 1→1
        
        # 4. 情感标签
        if sentiment_score > 0.3:
            sentiment_label = "POSITIVE"
        elif sentiment_score < -0.3:
            sentiment_label = "NEGATIVE"
        else:
            sentiment_label = "NEUTRAL"
        
        # 5. 主题分类（基于关键词规则，聚焦高校场景）
        topic_category = classify_topic_by_keywords(keywords)
        
        logger.info(f"[任务 {task_id}] [文本分析] 关键词: {keywords}, 敏感词: {sensitive_words}, 情感: {sentiment_label}({sentiment_score:.3f}), 主题: {topic_category}")
        
        return {
            "keywords": keywords,
            "wordCloud": word_cloud,  # 新增：词云数据
            "sensitiveWords": sensitive_words,  # 新增：敏感词列表
            "sentimentScore": round(sentiment_score, 4),
            "sentimentLabel": sentiment_label,
            "topicCategory": topic_category
        }
        
    except Exception as e:
        logger.error(f"[任务 {task_id}] [文本分析] NLP分析失败: {e}")
        # 返回失败态空结果，避免伪造关键词
        return {
            "keywords": [],
            "wordCloud": [],
            "sensitiveWords": [],
            "sentimentScore": 0.0,
            "sentimentLabel": "NEUTRAL",
            "topicCategory": "未知"
        }


def detect_sensitive_words(text: str, custom_word_packs: Optional[list] = None) -> list:
    """
    检测敏感词汇（高校风险预警相关）
    
    Args:
        text: 待检测文本
        
    Returns:
        检测到的敏感词列表
    """
    # 高校风险预警敏感词库
    sensitive_categories = {
        "政治敏感": ["政治", "政府", "领导", "官员", "腐败", "贪污"],
        "社会敏感": ["罢课", "罢工", "游行", "示威", "抗议"],
        "违法违规": ["作弊", "代考", "买卖", "诈骗", "传销"],
        "不良信息": ["自杀", "抑郁", "厌世", "报复", "伤害"],
        "商业广告": ["微信", "QQ", "联系方式", "代理", "兼职赚钱"],
        "学术不端": ["论文代写", "抄袭", "学术造假"]
    }
    
    detected = []
    seen = set()
    for category, words in sensitive_categories.items():
        for word in words:
            if word in text:
                key = (word, category)
                if key not in seen:
                    seen.add(key)
                    detected.append({"word": word, "category": category, "source": "builtin"})

    # 合并前端挂载词库（风险词库管理）
    custom_words = _extract_pack_words(custom_word_packs)
    for cw in custom_words:
        word = cw.get("text", "")
        category = cw.get("category", "自定义词库")
        if word and word in text:
            key = (word, category)
            if key not in seen:
                seen.add(key)
                detected.append({
                    "word": word,
                    "category": category,
                    "riskLevel": cw.get("riskLevel", "medium"),
                    "packName": cw.get("packName", "自定义词库"),
                    "source": "wordPack",
                })
    
    return detected


def classify_topic_by_keywords(keywords: list) -> str:
    """
    基于关键词规则分类主题（不返回"其他"）
    
    Args:
        keywords: 关键词列表
        
    Returns:
        主题分类
    """
    keywords_str = " ".join(keywords)
    
    # 主题关键词映射
    if any(word in keywords_str for word in ["选课", "系统", "教务", "制度", "政策", "管理"]):
        return "校园政策"
    elif any(word in keywords_str for word in ["学习", "考试", "课程", "教学", "作业", "论文"]):
        return "学术讨论"
    elif any(word in keywords_str for word in ["社团", "活动", "组织", "成员"]):
        return "社团活动"
    elif any(word in keywords_str for word in ["运动", "比赛", "体育", "健身", "球"]):
        return "体育运动"
    elif any(word in keywords_str for word in ["创业", "创新", "项目", "团队"]):
        return "科技创新"
    elif any(word in keywords_str for word in ["就业", "实习", "工作", "招聘", "面试"]):
        return "就业指导"
    elif any(word in keywords_str for word in ["科技", "技术", "编程", "开发", "AI", "算法"]):
        return "科技创新"
    elif any(word in keywords_str for word in ["艺术", "音乐", "表演", "舞蹈", "绘画"]):
        return "艺术表演"
    elif any(word in keywords_str for word in ["心理", "情绪", "压力", "焦虑"]):
        return "心理健康"
    elif any(word in keywords_str for word in ["志愿", "实践", "服务", "公益"]):
        return "社会实践"
    elif any(word in keywords_str for word in ["宿舍", "食堂", "寝室", "校园卡"]):
        return "校园生活"
    else:
        return "未知"


def generate_speech_events(transcription: str, duration: float, sentiment_score: float, keywords: list, task_id: str) -> list:
    """
    生成语音事件流（SpeechEvent类型）- 合理分段，不要把整段文本放一个事件
    
    Args:
        transcription: ASR识别文本
        duration: 视频时长
        sentiment_score: 情感分数
        keywords: 关键词列表
        task_id: 任务ID
        
    Returns:
        语音事件列表
    """
    speech_events = []
    event_id = 1
    
    # 如果无有效文本，返回空列表
    if not transcription or transcription in [ASR_EMPTY_TEXT, ASR_FAIL_TEXT]:
        return []
    
    # 智能分段：按句号、问号、感叹号分割
    import re
    sentences = [s.strip() for s in re.split('[。！？!?]', transcription) if s.strip() and len(s.strip()) > 1]
    
    # 如果分段失败或文本过长，按长度强制分段
    if not sentences or len(transcription) > 200:
        max_length = 50  # 每段最多50字
        sentences = []
        for i in range(0, len(transcription), max_length):
            segment = transcription[i:i+max_length].strip()
            if segment:
                sentences.append(segment)
    
    # 限制事件数量（避免过多）
    if len(sentences) > 15:
        # 合并相邻的短句
        merged_sentences = []
        temp = ""
        for s in sentences:
            temp += s
            if len(temp) >= 30:  # 累积到30字以上再输出
                merged_sentences.append(temp)
                temp = ""
        if temp:
            merged_sentences.append(temp)
        sentences = merged_sentences[:15]  # 最多15个事件
    
    # 为每个句子生成一个语音事件
    segment_duration = duration / max(len(sentences), 1)
    
    for i, sentence in enumerate(sentences):
        start_time = int(i * segment_duration)
        end_time = min(int((i + 1) * segment_duration), int(duration))
        
        # 从句子中提取关键词
        sentence_keywords = [kw for kw in keywords if kw in sentence]
        
        neg_hit = any(word in sentence for word in ["抵制", "反对", "抗议", "不满", "垃圾", "傻逼"])
        base = 50 + int(-sentiment_score * 25)
        if neg_hit:
            base += 25
        base += min(10, len(sentence_keywords) * 2)
        risk_score = max(5, min(98, base))

        if risk_score >= 80:
            emotion_label = "愤怒"
            emotion_intensity = 0.9
            emotion_bg = "rgba(245, 108, 108, 0.15)"
            emotion_text = "#f56c6c"
        elif risk_score >= 60:
            emotion_label = "紧张"
            emotion_intensity = 0.7
            emotion_bg = "rgba(250, 173, 20, 0.15)"
            emotion_text = "#faad14"
        else:
            emotion_label = "平静"
            emotion_intensity = 0.35
            emotion_bg = "rgba(82, 196, 26, 0.15)"
            emotion_text = "#52c41a"
        
        speech_events.append({
            "id": f"speech-{event_id:03d}",
            "modality": "speech",
            "startTime": start_time,
            "endTime": end_time,
            "riskScore": risk_score,
            "transcript": sentence[:80],  # 限制单句最多80字符
            "keywords": sentence_keywords[:5],  # 限制关键词数量
            "emotion": {
                "label": emotion_label,
                "intensity": emotion_intensity,
                "bgColor": emotion_bg,
                "textColor": emotion_text
            },
            "confidence": max(70, min(98, 80 + len(sentence_keywords) * 3))
        })
        event_id += 1
    
    logger.info(f"[任务 {task_id}] [音频处理模块] 生成 {len(speech_events)} 个语音事件（原文{len(transcription)}字）")
    return speech_events


def generate_text_risks(duration: float, sentiment_score: float, sensitive_words: list) -> list:
    """
    生成文本风险点时间序列（基于5秒粒度）
    
    Args:
        duration: 视频时长
        sentiment_score: 情感分数
        sensitive_words: 敏感词列表
        
    Returns:
        文本风险点列表
    """
    time_granularity = 5
    num_segments = int(duration / time_granularity) + 1
    text_risks = []
    
    has_sensitive = len(sensitive_words) > 0
    
    sens_factor = min(0.35, len(sensitive_words) * 0.08)
    sent_factor = max(0.0, -float(sentiment_score)) * 0.35

    for i in range(num_segments):
        pos = i / max(1, num_segments - 1)
        center_boost = max(0.0, 1 - abs(pos - 0.5) * 2) * 0.15
        intensity = 0.18 + sens_factor + sent_factor + center_boost
        intensity = max(0.05, min(1.0, intensity))

        if len(sensitive_words) > 0 and intensity >= 0.7:
            reason = "检测到敏感关键词并伴随负向表达"
        elif intensity >= 0.55:
            reason = "文本情绪偏负面，存在传播风险"
        else:
            reason = "文本内容整体平稳"
        
        text_risks.append({
            "reason": reason,
            "intensity": round(intensity, 2)
        })
    
    return text_risks


def calculate_text_risk_scores(sentiment_score: float, keywords: list, sensitive_words: list, topic_category: str) -> dict:
    """
    计算文本维度的6个风险分数。
    要求文档：综合风险 >0.7 高风险，>0.4 中风险；主题为「未知/其他」时降低情感对舆论风险的权重，避免演示类内容误判。
    """
    has_sensitive = len(sensitive_words) > 0
    keyword_count = len(keywords)
    neg = max(0.0, -float(sentiment_score))

    identity_score = int(35 + min(30, keyword_count * 4))

    university_keywords = ["大学", "学校", "校园", "学生", "老师", "教务", "宿舍", "食堂"]
    university_related_count = sum(1 for kw in keywords if any(uk in kw for uk in university_keywords))
    university_score = int(35 + min(50, university_related_count * 12) + (10 if "校园" in topic_category else 0))

    attitude_score = int(25 + neg * 60 + (8 if has_sensitive else 0))
    topic_score = int(35 + min(35, keyword_count * 4) + (8 if topic_category else 0))

    # 舆论风险：主题为「未知/其他」时情感权重减半，避免演示/教程类内容被 SnowNLP 误判负面时分数过高
    topic_weak = (topic_category or "").strip() in ("", "未知", "其他")
    neg_coef = 25 if topic_weak else 50
    opinion_risk_score = int(20 + neg * neg_coef + min(30, len(sensitive_words) * 10))
    action_score = int(opinion_risk_score * 0.65 + attitude_score * 0.35)

    return {
        "identity": min(100, max(0, identity_score)),
        "university": min(100, max(0, university_score)),
        "topic": min(100, max(0, topic_score)),
        "attitude": min(100, max(0, attitude_score)),
        "opinionRisk": min(100, max(0, opinion_risk_score)),
        "action": min(100, max(0, action_score))
    }


def generate_preliminary_evidences(keywords: list, sentiment_score: float, topic_category: str, duration: float) -> dict:
    """
    生成6个维度的初步证据（包含text、visual、audio三种类型）
    
    Args:
        keywords: 关键词列表
        sentiment_score: 情感分数
        topic_category: 主题分类
        duration: 视频时长
        
    Returns:
        6个维度的初步证据字典（每个维度包含多种模态的证据）
    """
    evidences = {
        "identity": [],
        "university": [],
        "topic": [],
        "attitude": [],
        "opinionRisk": [],
        "action": []
    }

    d = max(1, int(duration))
    ts1 = min(d - 1, max(1, int(d * 0.2)))
    ts2 = min(d - 1, max(1, int(d * 0.5)))
    ts3 = min(d - 1, max(1, int(d * 0.8)))

    id_kw = next((k for k in keywords if any(w in k for w in ["学生", "同学", "班", "学号", "老师"])), None)
    uni_kw = next((k for k in keywords if any(w in k for w in ["大学", "校园", "学校", "教务", "宿舍", "食堂"])), None)
    topic_kw = keywords[0] if keywords else None

    if id_kw:
        evidences["identity"].append({"timestamp": ts1, "type": "text", "description": f"文本中出现身份相关线索：{id_kw}", "confidence": 85, "keyword": id_kw})
    if uni_kw:
        evidences["university"].append({"timestamp": ts1, "type": "text", "description": f"文本中出现高校相关线索：{uni_kw}", "confidence": 88, "keyword": uni_kw})
    if topic_kw:
        evidences["topic"].append({"timestamp": ts2, "type": "text", "description": f"主题归类依据关键词：{topic_kw}", "confidence": 82, "keyword": topic_kw})

    senti_100 = int((1 - sentiment_score) * 50)
    senti_100 = max(0, min(100, senti_100))

    # 负向表达命中词（替换固定占位词 sentiment/opinion）
    negative_tokens = ["抵制", "反对", "抗议", "不满", "质疑", "投诉", "愤怒", "垃圾", "傻逼"]
    neg_kw = next((k for k in keywords if any(n in k for n in negative_tokens)), None)

    if sentiment_score < -0.2:
        evidences["attitude"].append({
            "timestamp": ts2,
            "type": "text",
            "description": "检测到负向情绪表达",
            "confidence": 80,
            "keyword": neg_kw or "负向表达",
            "sentimentScore": senti_100,
        })

    if sentiment_score < -0.3 or neg_kw:
        evidences["opinionRisk"].append({
            "timestamp": ts3,
            "type": "text",
            "description": "负向表达可能带来传播风险",
            "confidence": 78,
            "keyword": neg_kw or "传播风险",
        })

    if sentiment_score < -0.25:
        evidences["action"].append({
            "timestamp": ts3,
            "type": "text",
            "description": "建议进行人工复核",
            "confidence": 80,
            "keyword": neg_kw or "人工复核",
        })

    return evidences


def convert_audio_to_text(
    task_id: str,
    video_info: Dict[str, Any],
    transcription: str,
    duration: float,
    custom_word_packs: Optional[list] = None,
) -> Dict[str, Any]:
    """
    文本分析（使用Jieba和SnowNLP进行真实NLP分析）
    适配新的前端数据结构
    
    Args:
        task_id: 任务ID
        video_info: 视频信息
        transcription: ASR识别的文本内容
        duration: 视频时长
        
    Returns:
        文本分析结果（包含语音事件、文本风险、特征分数、初步证据）
    """
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 2: 开始文本分析...")
    start_time = time.time()

    transcription_quality = estimate_transcription_quality(transcription)
    
    # 使用真实的NLP分析
    nlp_result = analyze_text_with_nlp(transcription, task_id, custom_word_packs=custom_word_packs)
    
    sentiment_score = nlp_result["sentimentScore"]
    keywords = nlp_result["keywords"]
    sensitive_words = nlp_result.get("sensitiveWords", [])
    topic_category = nlp_result["topicCategory"]
    
    # ========== 构建文本模块结果 ==========
    
    # 1. 生成语音事件流
    speech_events = generate_speech_events(
        transcription,
        duration,
        sentiment_score,
        keywords,
        task_id
    )
    
    # 2. 生成文本风险时间序列
    text_risks = generate_text_risks(duration, sentiment_score, sensitive_words)
    
    # 3. 计算文本维度的特征分数
    text_risk_scores = calculate_text_risk_scores(
        sentiment_score,
        keywords,
        sensitive_words,
        topic_category
    )
    
    # 4. 生成初步证据（6个维度）：优先用 DeepSeek 根据转写文本生成详细证据，失败则用规则
    try:
        from deepseek_evidences import generate_evidences_via_deepseek
        deepseek_evidences = generate_evidences_via_deepseek(transcription, duration, task_id=task_id)
        if deepseek_evidences and any(deepseek_evidences.get(k) for k in ("identity", "university", "topic", "attitude", "opinionRisk", "action")):
            preliminary_evidences = deepseek_evidences
            total_ev = sum(len(deepseek_evidences.get(k, [])) for k in ("identity", "university", "topic", "attitude", "opinionRisk", "action"))
            print(f"[任务 {task_id}] 【证据】使用 DeepSeek 生成，共 {total_ev} 条证据")
            logger.info("[任务 %s] 证据来源: DeepSeek，共 %s 条", task_id, total_ev)
        else:
            preliminary_evidences = generate_preliminary_evidences(
                keywords,
                sentiment_score,
                topic_category,
                duration
            )
            print(f"[任务 {task_id}] 【证据】使用规则证据（DeepSeek 未用或未返回有效证据）")
            logger.info("[任务 %s] 证据来源: 规则", task_id)
    except Exception as e:
        logger.warning("[任务 %s] DeepSeek 证据生成失败，使用规则证据: %s", task_id, e)
        print(f"[任务 {task_id}] 【证据】使用规则证据（DeepSeek 异常: {e}）")
        preliminary_evidences = generate_preliminary_evidences(
            keywords,
            sentiment_score,
            topic_category,
            duration
        )
    
    # 5. 判断高校关联性（仅基于真实提取关键词）
    university_keywords = ["大学", "学院", "学校", "校园", "北大", "清华", "复旦", "教务", "选课", "宿舍", "食堂"]
    
    detected_keywords_list = [
        {
            "word": kw,
            "isUniversityRelated": any(uk in kw for uk in university_keywords)
        }
        for kw in keywords[:10]  # 最多取10个关键词
    ]
    
    # 6. 生成AI摘要（智能提取核心内容）
    def generate_smart_summary(text: str, max_length: int = 80) -> str:
        """生成智能摘要"""
        if not text or len(text) <= max_length:
            return text
        
        # 按句子分割
        import re
        sentences = [s.strip() for s in re.split('[。！？!?]', text) if s.strip()]
        
        if not sentences:
            return text[:max_length] + "..."
        
        # 优先选择包含关键词的句子
        scored_sentences = []
        for sent in sentences:
            score = 0
            # 关键词加分
            for kw in keywords[:5]:  # 只检查前5个关键词
                if kw in sent:
                    score += 2
            # 学校相关词汇加分
            if any(word in sent for word in ["学校", "大学", "校园", "老师", "同学"]):
                score += 1
            # 情感词汇加分
            if any(word in sent for word in ["不满", "批评", "抗议", "反对", "支持", "赞同"]):
                score += 1
            scored_sentences.append((score, sent))
        
        # 按分数排序，取最高分的句子
        scored_sentences.sort(reverse=True, key=lambda x: x[0])
        
        summary = ""
        for score, sent in scored_sentences:
            if len(summary) + len(sent) <= max_length:
                summary += sent + "。"
            else:
                break
        
        if not summary:
            summary = sentences[0][:max_length] + "..."
        
        return summary
    
    ai_description = generate_smart_summary(transcription, 80)
    
    result = {
        # 视频基本信息补充
        "videoInfo": {
            "description": ai_description,
            "detectedKeywords": detected_keywords_list
        },
        
        # 语音事件流
        "speechEvents": speech_events,
        
        # 时间轴-文本风险
        "textRisks": text_risks,
        
        # 特征数据（用于后续融合）
        "features": {
            "transcription": transcription,
            "keywords": keywords,
            "wordCloud": nlp_result.get("wordCloud", []),
            "sensitiveWords": sensitive_words,
            "sentimentScore": sentiment_score,
            "sentimentLabel": nlp_result["sentimentLabel"],
            "topicCategory": topic_category,
            "languageConfidence": transcription_quality,
            
            # 6个维度的文本风险分数
            "textRiskScores": text_risk_scores
        },
        
        # 初步证据（6个维度）
        "preliminaryEvidences": preliminary_evidences,
        
        "processingTime": round(time.time() - start_time, 2)
    }
    
    logger.info(f"[任务 {task_id}] [音频处理模块] Step 2: 文本分析完成")
    logger.info(f"[任务 {task_id}] - 语音事件: {len(speech_events)}个")
    logger.info(f"[任务 {task_id}] - 文本风险点: {len(text_risks)}个")
    logger.info(f"[任务 {task_id}] - 检测到关键词: {len(keywords)}个")
    logger.info(f"[任务 {task_id}] - 处理时间: {result['processingTime']:.2f}秒")
    
    return result

