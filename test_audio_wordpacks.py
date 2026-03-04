import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import 音频处理


class TestAudioWordPacksIntegration(unittest.TestCase):
    def setUp(self):
        self.word_packs = [
            {
                "packId": "p1",
                "name": "高校舆情",
                "words": [
                    {"text": "选课系统", "category": "校园政策", "riskLevel": "medium"},
                    {"text": "教务处", "category": "校园政策", "riskLevel": "low"},
                ],
            },
            {
                "packId": "p2",
                "name": "敏感舆情",
                "words": [
                    {"text": "抵制", "category": "社会敏感", "riskLevel": "high"},
                ],
            },
        ]

    def test_build_hotwords_from_word_packs(self):
        hotwords = 音频处理.build_hotwords_from_word_packs(self.word_packs, max_words=2)
        self.assertEqual(len(hotwords), 2)
        self.assertEqual(hotwords[0], "抵制")

    def test_build_whisper_initial_prompt(self):
        prompt = 音频处理.build_whisper_initial_prompt(["选课系统", "教务处"])
        self.assertIn("选课系统", prompt)
        self.assertIn("教务处", prompt)

    def test_asr_post_process_should_restore_punctuation_and_domain_terms(self):
        raw = "这次 选课系统 又 崩了 大家 都 在 抵制 教务处"
        processed = 音频处理.post_process_asr_text(raw, custom_word_packs=self.word_packs)

        # 应包含领域词
        self.assertIn("选课系统", processed)
        self.assertIn("教务处", processed)
        # 应有标点（模型或规则恢复都应生效）
        self.assertTrue(any(p in processed for p in ["，", "。", "！", "？"]))

    def test_estimate_transcription_quality(self):
        ok = "这次选课系统又崩了，大家都在抵制。"
        bad = 音频处理.ASR_FAIL_TEXT
        self.assertGreater(音频处理.estimate_transcription_quality(ok), 0.2)
        self.assertEqual(音频处理.estimate_transcription_quality(bad), 0.0)

    def test_generate_preliminary_evidences_should_not_use_fixed_placeholder_keywords(self):
        evidences = 音频处理.generate_preliminary_evidences(
            keywords=["抵制", "选课系统"],
            sentiment_score=-0.5,
            topic_category="校园政策",
            duration=60,
        )

        attitude_keywords = {e.get("keyword") for e in evidences.get("attitude", [])}
        opinion_keywords = {e.get("keyword") for e in evidences.get("opinionRisk", [])}

        self.assertNotIn("sentiment", attitude_keywords)
        self.assertNotIn("opinion", opinion_keywords)

    def test_detect_sensitive_words_with_custom_word_packs(self):
        text = "这次选课系统又崩了，大家都在抵制。"
        detected = 音频处理.detect_sensitive_words(text, custom_word_packs=self.word_packs)

        words = {item["word"] for item in detected}
        self.assertIn("选课系统", words)
        self.assertIn("抵制", words)


if __name__ == "__main__":
    unittest.main()
