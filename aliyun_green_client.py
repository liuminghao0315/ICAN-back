#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阿里云内容安全API客户端（增强版 CIP）
基于官方文档：green-cip 端点 + Action 式调用

支持：
- 文本同步：TextModerationPlus (Service: comment_detection_pro)
- 视频异步：VideoModeration / VideoModerationResult (Service: videoDetection)
"""

import json
import uuid
import time
import hmac
import base64
import hashlib
import urllib.request
import urllib.parse
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional


def _quote(key: str, value: str) -> str:
    """URL 编码 query 参数值（RFC3986 风格）"""
    s = urllib.parse.quote(str(value), safe="~")
    return s.replace("+", "%20").replace("*", "%2A")


def _percent_encode(s: str) -> str:
    """签名字符串用：对整串做 percentEncode（阿里云 RPC 规范）"""
    s = urllib.parse.quote(s, safe="~")
    return s.replace("+", "%20").replace("*", "%2A")


class AliyunGreenClient:
    """阿里云内容安全增强版客户端（green-cip）"""

    def __init__(self, access_key_id: str, access_key_secret: str, region: str = "cn-shanghai"):
        self.access_key_id = access_key_id
        self.access_key_secret = access_key_secret
        self.endpoint = f"https://green-cip.{region}.aliyuncs.com"
        self.version = "2022-03-02"

        print("[AliyunGreenClient] 初始化完成（增强版 green-cip）")
        print(f"[AliyunGreenClient] Endpoint: {self.endpoint}")
        print(f"[AliyunGreenClient] AccessKeyId: {access_key_id[:8]}****")

    def _get_gmt_date(self) -> str:
        from email.utils import formatdate
        return formatdate(usegmt=True)

    def _get_timestamp_iso8601(self) -> str:
        """ISO8601 UTC 时间戳，green-cip 要求 Timestamp / x-acs-date"""
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def _request_cip(self, action: str, body: Dict[str, Any]) -> Dict[str, Any]:
        """
        增强版统一请求：签名字符串包含 query 公共参数 + Service + ServiceParameters（JSON 字符串）。
        Service/ServiceParameters 仅参与签名；请求体仍为 JSON body。
        """
        body_json = json.dumps(body)
        body_data = body_json.encode("utf-8")
        gmt_date = self._get_gmt_date()
        timestamp_iso = self._get_timestamp_iso8601()
        signature_nonce = str(uuid.uuid4())

        service_params_str = json.dumps(body["ServiceParameters"], ensure_ascii=False, separators=(",", ":"))

        query_params_no_sig = [
            ("AccessKeyId", self.access_key_id),
            ("Action", action),
            ("Format", "JSON"),
            ("Service", body["Service"]),
            ("ServiceParameters", service_params_str),
            ("SignatureMethod", "HMAC-SHA1"),
            ("SignatureNonce", signature_nonce),
            ("SignatureVersion", "1.0"),
            ("Timestamp", timestamp_iso),
            ("Version", self.version),
        ]
        query_params_no_sig.sort(key=lambda x: x[0])
        canonicalized_query = "&".join([f"{_quote(k, k)}={_quote(k, v)}" for k, v in query_params_no_sig])

        string_to_sign = f"POST&%2F&{_percent_encode(canonicalized_query)}"
        key = (self.access_key_secret + "&").encode("utf-8")
        signature_raw = hmac.new(key, string_to_sign.encode("utf-8"), hashlib.sha1).digest()
        signature = base64.b64encode(signature_raw).decode("utf-8")

        query_params = query_params_no_sig + [("Signature", signature)]
        url_query = "&".join([f"{k}={_quote(k, v)}" for k, v in query_params])
        url = f"{self.endpoint}/?{url_query}"

        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Date": gmt_date,
        }

        req = urllib.request.Request(url, data=body_data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8")
            print(f"[AliyunGreenClient] HTTP错误: {e.code}")
            print(f"[AliyunGreenClient] 错误详情: {err_body}")
            try:
                return json.loads(err_body)
            except Exception:
                return {"Code": e.code, "Message": err_body}
        except Exception as e:
            print(f"[AliyunGreenClient] 请求异常: {e}")
            return {"Code": 500, "Message": str(e)}

    def _code(self, r: Dict[str, Any]) -> int:
        """兼容 Code / code"""
        return r.get("Code", r.get("code", 0))

    def _data(self, r: Dict[str, Any]) -> Dict:
        """兼容 Data / data"""
        return r.get("Data", r.get("data", {}) or {})

    def _message(self, r: Dict[str, Any]) -> str:
        return r.get("Message", r.get("msg", ""))

    # ==================== 文本审核（增强版）====================

    def text_scan(self, content: str, data_id: str = None) -> Dict[str, Any]:
        """
        文本同步检测（TextModerationPlus）。
        Service: comment_detection_pro；文本限定 600 字符。
        """
        if data_id is None:
            data_id = str(uuid.uuid4())
        content = content[:600]

        body = {
            "Service": "comment_detection_pro",
            "ServiceParameters": {
                "dataId": data_id,
                "content": content,
            },
        }

        print(f"[文本检测-增强版] 开始检测, 文本长度: {len(content)}")
        result = self._request_cip("TextModerationPlus", body)
        print(f"[文本检测-增强版] 响应 Code: {self._code(result)}")

        return self._parse_text_result(result)

    def _parse_text_result(self, response: Dict) -> Dict[str, Any]:
        """解析增强版文本结果：Data.Result[], Data.RiskLevel -> passed/label/violations"""
        code = self._code(response)
        if code != 200:
            return {
                "passed": False,
                "label": "error",
                "suggestion": "block",
                "confidence": 0,
                "violations": [self._message(response) or "API调用失败"],
            }

        data = self._data(response)
        result_list = data.get("Result", data.get("result", [])) or []
        risk_level = (data.get("RiskLevel") or data.get("riskLevel") or "none").lower()

        violations = []
        max_confidence = 0.0
        for item in result_list:
            label = item.get("Label", item.get("label", ""))
            if not label or str(label).lower() == "nonlabel":
                continue
            violations.append(label)
            conf = item.get("Confidence", item.get("confidence", 0))
            if isinstance(conf, (int, float)) and conf > max_confidence:
                max_confidence = float(conf)

        passed = risk_level in ("none", "low") and not violations
        if risk_level in ("high", "medium") or violations:
            passed = False

        suggestion = "block" if risk_level == "high" or violations else ("review" if risk_level == "medium" else "pass")
        confidence = max_confidence / 100.0 if max_confidence > 1 else (max_confidence if max_confidence else (0.99 if passed else 0.95))

        return {
            "passed": passed,
            "label": violations[0] if violations else "normal",
            "suggestion": suggestion,
            "confidence": confidence,
            "violations": violations,
        }

    # ==================== 视频审核（增强版）====================

    def video_async_scan(self, video_url: str, data_id: str = None) -> Dict[str, Any]:
        """视频异步提交（VideoModeration）。Service: videoDetection。"""
        if data_id is None:
            data_id = str(uuid.uuid4())

        body = {
            "Service": "videoDetection",
            "ServiceParameters": {
                "url": video_url,
                "dataId": data_id,
            },
        }

        print("[视频检测-增强版] 提交任务...")
        print(f"[视频检测-增强版] URL: {video_url[:100]}...")
        result = self._request_cip("VideoModeration", body)
        print(f"[视频检测-增强版] 提交响应 Code: {self._code(result)}")

        if self._code(result) != 200:
            return {"error": self._message(result) or "提交失败", "code": self._code(result)}

        data = self._data(result)
        task_id = data.get("TaskId", data.get("taskId"))
        if task_id:
            print(f"[视频检测-增强版] 任务ID: {task_id}")
            return {"taskId": task_id, "dataId": data_id}
        return {"error": "未获取到任务ID"}

    def video_get_results(self, task_id: str) -> Dict[str, Any]:
        """查询视频结果（VideoModerationResult）。"""
        print(f"[视频检测-增强版] 查询结果: {task_id}")
        body = {
            "Service": "videoDetection",
            "ServiceParameters": {"taskId": task_id},
        }
        result = self._request_cip("VideoModerationResult", body)
        print(f"[视频检测-增强版] 查询响应 Code: {self._code(result)}")
        return result

    def video_scan_sync(
        self,
        video_url: str,
        data_id: str = None,
        scenes: List[str] = None,
        poll_interval: int = 5,
        max_wait: int = 300,
    ) -> Dict[str, Any]:
        """视频同步：提交 + 轮询；Code 280=检测中，200=完成。"""
        submit_result = self.video_async_scan(video_url, data_id)

        if "error" in submit_result:
            return {
                "passed": False,
                "label": "error",
                "suggestion": "block",
                "confidence": 0,
                "violations": [submit_result["error"]],
            }

        task_id = submit_result["taskId"]
        # 文档建议：提交任务后约 30 秒再查结果，首次轮询前先等待
        first_delay = min(30, poll_interval * 2)
        print(f"[视频检测-增强版] 任务已提交，{first_delay} 秒后开始查询结果...")
        time.sleep(first_delay)

        start_time = time.time()
        attempt = 0

        while time.time() - start_time < max_wait:
            attempt += 1
            elapsed = int(time.time() - start_time)
            print(f"[视频检测-增强版] 第{attempt}次查询 (已等待{elapsed}秒)...")

            result = self.video_get_results(task_id)
            code = self._code(result)
            msg = self._message(result)

            if code == 280:
                print("[视频检测-增强版] 处理中...")
                time.sleep(poll_interval)
                continue
            if code == 200:
                print("[视频检测-增强版] 处理完成!")
                data = self._data(result)
                return self._parse_video_result(data)

            # 404/403 可能表示结果尚未就绪，继续轮询
            if code in (404, 403) and attempt < 12:
                print(f"[视频检测-增强版] 结果未就绪 (Code={code})，{poll_interval}秒后重试...")
                time.sleep(poll_interval)
                continue

            print(f"[视频检测-增强版] 异常 Code: {code}, Message: {msg}")
            return {
                "passed": False,
                "label": "error",
                "suggestion": "block",
                "confidence": 0,
                "violations": [msg or f"异常状态码:{code}"],
            }

        print(f"[视频检测-增强版] 超时! 等待了{max_wait}秒")
        return {
            "passed": False,
            "label": "timeout",
            "suggestion": "review",
            "confidence": 0,
            "violations": [f"审核超时({max_wait}秒)"],
        }

    def _parse_video_result(self, data: Dict) -> Dict[str, Any]:
        """解析增强版视频结果：Data.RiskLevel、FrameResult、AudioResult -> passed/label/violations"""
        risk_level = (data.get("RiskLevel") or data.get("riskLevel") or "none").lower()
        violations = []

        frame_result = data.get("FrameResult", data.get("frameResult")) or {}
        frame_summary = (frame_result.get("FrameSummarys") or frame_result.get("frameSummarys") or [])
        for s in frame_summary:
            label = s.get("Label", s.get("label", ""))
            if label and str(label).lower() != "nonlabel":
                violations.append(f"frame:{label}")

        audio_result = data.get("AudioResult", data.get("audioResult")) or {}
        slice_details = (audio_result.get("SliceDetails") or audio_result.get("sliceDetails") or [])
        for s in slice_details:
            labels = s.get("Labels", s.get("labels", ""))
            if labels:
                for lb in labels.split(","):
                    lb = lb.strip()
                    if lb and lb.lower() != "nonlabel":
                        violations.append(f"audio:{lb}")

        passed = risk_level == "none" and not violations
        if risk_level in ("high", "medium") or violations:
            passed = False
        suggestion = "block" if risk_level == "high" or violations else ("review" if risk_level == "medium" else "pass")

        return {
            "passed": passed,
            "label": violations[0].split(":", 1)[-1] if violations else "normal",
            "suggestion": suggestion,
            "confidence": 0.99 if passed else 0.95,
            "violations": violations if violations else ([risk_level] if risk_level != "none" else []),
        }


_client: Optional[AliyunGreenClient] = None


def get_green_client(
    access_key_id: str = None,
    access_key_secret: str = None,
) -> AliyunGreenClient:
    global _client
    if _client is None:
        if access_key_id is None or access_key_secret is None:
            raise ValueError("首次调用必须提供 access_key_id 和 access_key_secret")
        _client = AliyunGreenClient(access_key_id, access_key_secret)
    return _client


if __name__ == "__main__":
    ACCESS_KEY_ID = "LTAI5tMnc6fAwoqpaSd3pybw"
    ACCESS_KEY_SECRET = "GLYY1Jed0MqP86ZJKbkQoq5zEVMrcD"
    client = AliyunGreenClient(ACCESS_KEY_ID, ACCESS_KEY_SECRET)
    result = client.text_scan("这是一段正常的校园生活分享文本")
    print("结果:", result)
