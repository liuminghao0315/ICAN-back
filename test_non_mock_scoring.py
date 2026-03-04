import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import 音频处理
import 视频处理
import numpy as np


class TestNonMockScoring(unittest.TestCase):
    def test_audio_risk_scores_should_be_deterministic(self):
        s1 = 音频处理.calculate_audio_risk_scores(0.82, 0.63, 0.51)
        s2 = 音频处理.calculate_audio_risk_scores(0.82, 0.63, 0.51)
        self.assertEqual(s1, s2)

    def test_visual_risk_scores_should_be_deterministic(self):
        s1 = 视频处理.calculate_visual_risk_scores("教室讲课", True, 0.78)
        s2 = 视频处理.calculate_visual_risk_scores("教室讲课", True, 0.78)
        self.assertEqual(s1, s2)

    def test_visual_risk_should_reflect_quality_drop(self):
        high_quality = 视频处理.calculate_visual_risk_scores("教室讲课", True, 0.9)
        low_quality = 视频处理.calculate_visual_risk_scores("教室讲课", True, 0.3)

        self.assertGreaterEqual(low_quality["opinionRisk"], high_quality["opinionRisk"])
        self.assertGreaterEqual(low_quality["action"], high_quality["action"])

    def test_voice_emotion_rule_is_deterministic(self):
        e1 = 音频处理.infer_voice_emotion_from_features(0.8, 0.75, 0.2)
        e2 = 音频处理.infer_voice_emotion_from_features(0.8, 0.75, 0.2)
        self.assertEqual(e1, e2)
        self.assertIn(e1, ["calm", "neutral", "energetic", "tense"])

    def test_optional_yolo_detector_should_not_crash(self):
        frame = np.zeros((320, 320, 3), dtype=np.uint8)
        detections = 视频处理.detect_objects_optional(frame)
        self.assertIsInstance(detections, list)

    def test_build_open_vocab_prompts_from_wordpacks(self):
        packs = [
            {"packId": "1", "name": "高校", "words": [{"text": "校徽"}, {"text": "横幅"}]},
            {"packId": "2", "name": "舆情", "words": [{"text": "抗议"}]},
        ]
        prompts = 视频处理.build_open_vocab_prompts(packs)
        self.assertIn("校徽", prompts)
        self.assertIn("横幅", prompts)
        self.assertIn("抗议", prompts)

    def test_map_open_vocab_detections_to_risk(self):
        detections = [
            {"label": "横幅", "confidence": 0.82},
            {"label": "人群聚集", "confidence": 0.76},
        ]
        risk = 视频处理.map_open_vocab_detections_to_risk(detections)
        self.assertGreaterEqual(risk["riskScore"], 0.5)
        self.assertTrue(len(risk["riskFactors"]) >= 1)

    def test_build_audio_open_vocab_prompts(self):
        packs = [{"packId": "a1", "words": [{"text": "喊叫"}, {"text": "争吵"}]}]
        prompts = 音频处理.build_audio_open_vocab_prompts(packs)
        self.assertIn("喊叫", prompts)
        self.assertIn("争吵", prompts)

    def test_map_audio_open_vocab_to_risk(self):
        audio_hits = [
            {"label": "shouting", "confidence": 0.8},
            {"label": "争吵", "confidence": 0.7},
        ]
        risk = 音频处理.map_audio_open_vocab_to_risk(audio_hits)
        self.assertGreaterEqual(risk["riskScore"], 0.5)
        self.assertTrue(len(risk["riskFactors"]) >= 1)


if __name__ == "__main__":
    unittest.main()
