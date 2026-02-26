package com.ican.project.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.ican.project.model.entity.AnalysisResult;
import com.ican.project.model.entity.Video;
import com.ican.project.service.MinioService;
import com.ican.project.service.ReportPdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * PDF报告生成服务实现
 * 使用 Thymeleaf 渲染 HTML 模板，Flying Saucer 转换为 PDF
 */
@Service
public class ReportPdfServiceImpl implements ReportPdfService {

    private static final Logger logger = LoggerFactory.getLogger(ReportPdfServiceImpl.class);

    @Autowired
    private TemplateEngine templateEngine;

    @Autowired
    private MinioService minioService;

    @Override
    public String generateAndUpload(AnalysisResult result, Video video) {
        long start = System.currentTimeMillis();
        logger.info("开始生成PDF报告: resultId={}, videoId={}", result.getId(), video.getId());

        try {
            // 1. 构建 Thymeleaf 上下文
            Context ctx = buildContext(result, video);

            // 2. 渲染 HTML
            String html = templateEngine.process("report", ctx);

            // 3. HTML → PDF
            byte[] pdfBytes = renderPdf(html);

            // 4. 计算 MinIO 存储路径（与视频路径对应，前缀 reports/，后缀 .pdf）
            String pdfObjectName = derivePdfPath(video.getFilePath());

            // 5. 上传到 MinIO
            try (ByteArrayInputStream bis = new ByteArrayInputStream(pdfBytes)) {
                minioService.uploadFile(pdfObjectName, bis, "application/pdf", pdfBytes.length);
            }

            long elapsed = System.currentTimeMillis() - start;
            logger.info("PDF报告生成并上传完成: path={}, size={}KB, 耗时={}ms",
                    pdfObjectName, pdfBytes.length / 1024, elapsed);

            return pdfObjectName;

        } catch (Exception e) {
            logger.error("PDF报告生成失败: resultId={}, error={}", result.getId(), e.getMessage(), e);
            throw new RuntimeException("PDF报告生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据视频的 MinIO 路径推导 PDF 存储路径
     * videos/2026/02/26/abc123.mp4 → reports/2026/02/26/abc123.pdf
     */
    private String derivePdfPath(String videoFilePath) {
        if (videoFilePath == null || videoFilePath.isEmpty()) {
            String fallback = "reports/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                    + "/" + System.currentTimeMillis() + ".pdf";
            logger.warn("视频路径为空，使用回退路径: {}", fallback);
            return fallback;
        }
        String path = videoFilePath.replaceFirst("^videos/", "reports/");
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx > 0) {
            path = path.substring(0, dotIdx) + ".pdf";
        } else {
            path = path + ".pdf";
        }
        return path;
    }

    /**
     * 使用 Flying Saucer 将 XHTML 渲染为 PDF 字节数组
     */
    private byte[] renderPdf(String html) throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();

            ITextFontResolver fontResolver = renderer.getFontResolver();
            registerSystemFonts(fontResolver);

            // Thymeleaf HTML 模式输出可能不是严格 XHTML，需要确保 XML 声明存在
            if (!html.startsWith("<?xml")) {
                html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + html;
            }

            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(os);
            return os.toByteArray();
        }
    }

    /**
     * 注册系统中文字体
     */
    private void registerSystemFonts(ITextFontResolver fontResolver) {
        String[] fontPaths = {
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/simhei.ttf",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        };
        for (String path : fontPaths) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    fontResolver.addFont(path, com.lowagie.text.pdf.BaseFont.IDENTITY_H, com.lowagie.text.pdf.BaseFont.EMBEDDED);
                    logger.debug("已注册字体: {}", path);
                }
            } catch (Exception e) {
                logger.debug("注册字体失败(可忽略): {} - {}", path, e.getMessage());
            }
        }
    }

    /**
     * 构建 Thymeleaf 模板上下文（与前端 ReportView.vue 完全对齐）
     */
    private Context buildContext(AnalysisResult result, Video video) {
        Context ctx = new Context();

        // ===== 封面信息 =====
        ctx.setVariable("videoId", video.getId() != null ? video.getId() : "—");
        ctx.setVariable("fileName", video.getFileName() != null ? video.getFileName() : "未知文件");
        ctx.setVariable("duration", formatDuration(video.getDuration()));
        String uploadSource = Video.SourceType.URL_IMPORT.name().equals(video.getSourceType()) ? "网络采集" : "本地上传";
        ctx.setVariable("uploadSource", uploadSource);
        ctx.setVariable("sourceUrl", Video.SourceType.URL_IMPORT.name().equals(video.getSourceType()) ? video.getSourceUrl() : null);
        ctx.setVariable("reportTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")));

        // ===== 综合结论 =====
        ctx.setVariable("actionSuggestion", result.getActionSuggestion());
        ctx.setVariable("actionDetail", result.getActionDetail());
        ctx.setVariable("identityLabel", result.getIdentityLabel());
        ctx.setVariable("universityName", result.getUniversityName());

        Map<String, Object> identityFusion = parseJsonMap(result.getIdentityFusion());
        Map<String, Object> universityFusion = parseJsonMap(result.getUniversityFusion());
        Map<String, Object> actionFusion = parseJsonMap(result.getActionFusion());

        ctx.setVariable("identityFinalScore", safeGetInt(identityFusion, "finalScore"));
        ctx.setVariable("universityFinalScore", safeGetInt(universityFusion, "finalScore"));
        int actionFinalScore = safeGetInt(actionFusion, "finalScore");
        ctx.setVariable("actionFinalScore", actionFinalScore);

        // ===== 一、视频概览 =====
        ctx.setVariable("aiDescription", result.getAiDescription());
        ctx.setVariable("mainCharacter", parseJsonMap(result.getMainCharacter()));
        ctx.setVariable("detectedKeywords", parseJsonList(result.getDetectedKeywords()));

        // ===== 二、风险画像 =====
        List<Integer> averageRadarData = parseIntList(result.getAverageRadarData());
        ctx.setVariable("averageRadarData", averageRadarData);

        String[] radarNames = {"身份置信", "学校关联", "负面情感", "传播风险", "影响范围", "处置紧迫"};
        List<Map<String, Object>> radarRows = new java.util.ArrayList<>();
        for (int i = 0; i < radarNames.length; i++) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", radarNames[i]);
            row.put("value", i < averageRadarData.size() ? averageRadarData.get(i) : 0);
            radarRows.add(row);
        }
        ctx.setVariable("radarRows", radarRows);

        double videoContrib = safeGetDouble(actionFusion, "videoContribution");
        double audioContrib = safeGetDouble(actionFusion, "audioContribution");
        double textContrib = safeGetDouble(actionFusion, "textContribution");
        double contribTotal = videoContrib + audioContrib + textContrib;
        if (contribTotal > 0) {
            ctx.setVariable("contribVideo", String.format("%.1f", videoContrib / contribTotal * 100));
            ctx.setVariable("contribAudio", String.format("%.1f", audioContrib / contribTotal * 100));
            ctx.setVariable("contribText", String.format("%.1f", textContrib / contribTotal * 100));
        } else {
            ctx.setVariable("contribVideo", "0.0");
            ctx.setVariable("contribAudio", "0.0");
            ctx.setVariable("contribText", "0.0");
        }

        // ===== 三、风险走势分析 =====
        List<Map<String, Object>> comprehensiveRisks = parseJsonList(result.getComprehensiveRisks());
        int granularity = result.getTimeGranularity() != null ? result.getTimeGranularity() : 5;
        List<Map<String, Object>> riskTrend = new java.util.ArrayList<>();
        double peakIntensity = 0;
        int peakIndex = 0;
        for (int i = 0; i < comprehensiveRisks.size(); i++) {
            Map<String, Object> point = comprehensiveRisks.get(i);
            double intensity = toDouble(point.get("intensity"));
            if (intensity > peakIntensity) {
                peakIntensity = intensity;
                peakIndex = i;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("timeLabel", formatTimeRange(i * granularity, (i + 1) * granularity));
            row.put("intensity", String.format("%.1f%%", intensity * 100));
            row.put("intensityNum", intensity);
            riskTrend.add(row);
        }
        ctx.setVariable("riskTrend", riskTrend);

        String peakTime = formatTimeRange(peakIndex * granularity, (peakIndex + 1) * granularity);
        ctx.setVariable("riskPeakTime", peakTime);
        ctx.setVariable("riskPeakIntensity", peakIntensity);
        ctx.setVariable("riskPeakPercent", String.format("%.1f", peakIntensity * 100));

        // 查找峰值时段的触发事件
        List<Map<String, Object>> allEvents = parseJsonList(result.getTimelineEvents());
        int peakStartSec = peakIndex * granularity;
        int peakEndSec = (peakIndex + 1) * granularity;
        String triggerEvent = findTriggerEvent(allEvents, peakStartSec, peakEndSec);
        ctx.setVariable("riskPeakTrigger", triggerEvent);

        // ===== 四、六大维度分析详情 =====
        Map<String, Object> topicFusion = parseJsonMap(result.getTopicFusion());
        Map<String, Object> opinionRiskFusion = parseJsonMap(result.getOpinionRiskFusion());

        List<Map<String, Object>> identityEvidences = parseJsonList(result.getIdentityEvidences());
        List<Map<String, Object>> universityEvidences = parseJsonList(result.getUniversityEvidences());
        List<Map<String, Object>> topicEvidences = parseJsonList(result.getTopicEvidences());
        List<Map<String, Object>> attitudeEvidences = parseJsonList(result.getAttitudeEvidences());
        List<Map<String, Object>> opinionRiskEvidences = parseJsonList(result.getOpinionRiskEvidences());
        List<Map<String, Object>> actionEvidences = parseJsonList(result.getActionEvidences());

        // 态度统计
        int positive = 0, neutral = 0, negative = 0;
        for (Map<String, Object> ev : attitudeEvidences) {
            double score = toDouble(ev.get("sentimentScore"));
            if (score == 0) score = 50;
            if (score < 33.3) positive++;
            else if (score > 66.7) negative++;
            else neutral++;
        }
        int attTotal = attitudeEvidences.size();
        String posPercent = attTotal > 0 ? String.format("%.1f", (double) positive / attTotal * 100) : "0.0";
        String negPercent = attTotal > 0 ? String.format("%.1f", (double) negative / attTotal * 100) : "0.0";
        String neuPercent = attTotal > 0 ? String.format("%.1f", (double) neutral / attTotal * 100) : "0.0";

        List<Map<String, Object>> dimensions = new java.util.ArrayList<>();
        dimensions.add(buildDimension("1. 身份判定", "置信度 " + safeGetInt(identityFusion, "finalScore") + "%",
                safeGetInt(identityFusion, "finalScore"),
                List.of(Map.of("label", "判定结果", "value", nullSafe(result.getIdentityLabel()))),
                safeGetInt(identityFusion, "videoScore"), safeGetInt(identityFusion, "audioScore"), safeGetInt(identityFusion, "textScore"),
                identityEvidences.size()));
        dimensions.add(buildDimension("2. 涉及高校", "关联度 " + safeGetInt(universityFusion, "finalScore") + "%",
                safeGetInt(universityFusion, "finalScore"),
                List.of(Map.of("label", "识别结果", "value", nullSafe(result.getUniversityName()))),
                safeGetInt(universityFusion, "videoScore"), safeGetInt(universityFusion, "audioScore"), safeGetInt(universityFusion, "textScore"),
                universityEvidences.size()));
        dimensions.add(buildDimension("3. 内容主题", "相关度 " + safeGetInt(topicFusion, "finalScore") + "%",
                safeGetInt(topicFusion, "finalScore"),
                List.of(Map.of("label", "主题分类", "value", nullSafe(result.getTopicCategory())),
                        Map.of("label", "细分主题", "value", nullSafe(result.getTopicSubCategory()))),
                safeGetInt(topicFusion, "videoScore"), safeGetInt(topicFusion, "audioScore"), safeGetInt(topicFusion, "textScore"),
                topicEvidences.size()));
        dimensions.add(buildDimension("4. 对学校态度", "正" + posPercent + "% / 负" + negPercent + "%",
                -1,
                List.of(Map.of("label", "正面", "value", positive + "条 (" + posPercent + "%)"),
                        Map.of("label", "中性", "value", neutral + "条 (" + neuPercent + "%)"),
                        Map.of("label", "负面", "value", negative + "条 (" + negPercent + "%)")),
                null, null, null, attitudeEvidences.size()));
        dimensions.add(buildDimension("5. 潜在舆论风险", "风险值 " + safeGetInt(opinionRiskFusion, "finalScore") + "%",
                safeGetInt(opinionRiskFusion, "finalScore"),
                List.of(Map.of("label", "风险原因", "value", nullSafe(result.getOpinionRiskReason()))),
                safeGetInt(opinionRiskFusion, "videoScore"), safeGetInt(opinionRiskFusion, "audioScore"), safeGetInt(opinionRiskFusion, "textScore"),
                opinionRiskEvidences.size()));
        dimensions.add(buildDimension("6. 处置建议", nullSafe(result.getActionSuggestion()),
                safeGetInt(actionFusion, "finalScore"),
                List.of(Map.of("label", "详细说明", "value", nullSafe(result.getActionDetail()))),
                safeGetInt(actionFusion, "videoScore"), safeGetInt(actionFusion, "audioScore"), safeGetInt(actionFusion, "textScore"),
                actionEvidences.size()));

        // 两两配对，供模板 2 列布局
        List<List<Map<String, Object>>> dimensionPairs = new java.util.ArrayList<>();
        for (int i = 0; i < dimensions.size(); i += 2) {
            List<Map<String, Object>> pair = new java.util.ArrayList<>();
            pair.add(dimensions.get(i));
            if (i + 1 < dimensions.size()) pair.add(dimensions.get(i + 1));
            dimensionPairs.add(pair);
        }
        ctx.setVariable("dimensionPairs", dimensionPairs);

        // ===== 五、详细证据清单 =====
        List<Map<String, Object>> eventRows = new java.util.ArrayList<>();
        for (Map<String, Object> ev : allEvents) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            double startTime = toDouble(ev.get("startTime"));
            double endTime = toDouble(ev.get("endTime"));
            row.put("timeRange", formatTimeSec(startTime) + "-" + formatTimeSec(endTime));
            String modality = String.valueOf(ev.getOrDefault("modality", ""));
            row.put("modalityText", modalityToText(modality));
            double riskScore = toDouble(ev.get("riskScore"));
            row.put("riskScore", riskScore);
            row.put("confidence", toDouble(ev.get("confidence")));

            String content = "";
            if ("speech".equals(modality)) {
                content = String.valueOf(ev.getOrDefault("transcript", ""));
                Object kws = ev.get("keywords");
                if (kws instanceof List && !((List<?>) kws).isEmpty()) {
                    content += "（" + String.join("、", ((List<?>) kws).stream().map(String::valueOf).toList()) + "）";
                }
            } else if ("visual".equals(modality)) {
                content = String.valueOf(ev.getOrDefault("detectionLabel", ""));
            } else if ("audio-effect".equals(modality)) {
                content = String.valueOf(ev.getOrDefault("description", ""));
            }
            row.put("content", content);
            eventRows.add(row);
        }
        ctx.setVariable("timelineEvents", eventRows);

        // ===== 六、场景识别轨迹 =====
        List<Map<String, Object>> scenes = parseJsonList(result.getSceneRecognition());
        List<Map<String, Object>> sceneRows = new java.util.ArrayList<>();
        for (Map<String, Object> scene : scenes) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", scene.getOrDefault("name", "—"));
            double ts = toDouble(scene.get("timeStart"));
            double te = toDouble(scene.get("timeEnd"));
            row.put("timeRange", formatTimeSec(ts) + " - " + formatTimeSec(te));
            double conf = toDouble(scene.get("confidence"));
            row.put("confidenceText", String.format("%.0f%%", conf * 100));
            sceneRows.add(row);
        }
        ctx.setVariable("sceneRecognition", sceneRows);

        return ctx;
    }

    private Map<String, Object> buildDimension(String title, String scoreLabel, int finalScore,
                                                List<Map<String, String>> rows,
                                                Integer video, Integer audio, Integer text, int evidenceCount) {
        Map<String, Object> dim = new java.util.LinkedHashMap<>();
        dim.put("title", title);
        dim.put("scoreLabel", scoreLabel);
        String scoreClass = finalScore >= 67 ? "sc-high" : (finalScore >= 34 ? "sc-mid" : "sc-low");
        if (finalScore < 0) scoreClass = "";
        dim.put("scoreClass", scoreClass);
        dim.put("rows", rows);
        dim.put("video", video);
        dim.put("audio", audio);
        dim.put("text", text);
        dim.put("evidenceCount", evidenceCount);
        return dim;
    }

    private String findTriggerEvent(List<Map<String, Object>> events, int peakStartSec, int peakEndSec) {
        List<Map<String, Object>> inPeak = events.stream()
                .filter(e -> toDouble(e.get("startTime")) >= peakStartSec && toDouble(e.get("startTime")) < peakEndSec)
                .toList();
        List<Map<String, Object>> candidates = inPeak.isEmpty() ? events : inPeak;
        if (candidates.isEmpty()) return "未知事件";

        Map<String, Object> top = candidates.stream()
                .reduce((a, b) -> toDouble(b.get("riskScore")) > toDouble(a.get("riskScore")) ? b : a)
                .orElse(candidates.get(0));

        String modality = String.valueOf(top.getOrDefault("modality", ""));
        if ("speech".equals(modality)) {
            String transcript = String.valueOf(top.getOrDefault("transcript", ""));
            String truncated = transcript.length() > 30 ? transcript.substring(0, 30) + "..." : transcript;
            return "语音内容「" + truncated + "」";
        } else if ("visual".equals(modality)) {
            return "视觉特征「" + top.getOrDefault("detectionLabel", "") + "」";
        } else if ("audio-effect".equals(modality)) {
            return "声学特征「" + top.getOrDefault("description", "") + "」";
        }
        return "未知事件";
    }

    private String modalityToText(String modality) {
        if ("speech".equals(modality)) return "语音";
        if ("visual".equals(modality)) return "视觉";
        if ("audio-effect".equals(modality)) return "声学";
        return modality;
    }

    private String formatTimeRange(int startSec, int endSec) {
        return formatTimeSec(startSec) + " - " + formatTimeSec(endSec);
    }

    private String formatTimeSec(double seconds) {
        int total = (int) seconds;
        int m = total / 60;
        int s = total % 60;
        return String.format("%02d:%02d", m, s);
    }

    private int safeGetInt(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object val = map.get(key);
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private double safeGetDouble(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object val = map.get(key);
        return toDouble(val);
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0; }
    }

    private String nullSafe(String val) {
        return val != null ? val : "—";
    }

    private List<Integer> parseIntList(String json) {
        if (json == null || json.isEmpty()) return List.of();
        try {
            return JSON.parseObject(json, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String formatDuration(Double seconds) {
        if (seconds == null || seconds <= 0) return "未知";
        int total = seconds.intValue();
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format("%d时%02d分%02d秒", h, m, s);
        if (m > 0) return String.format("%d分%02d秒", m, s);
        return String.format("%d秒", s);
    }

    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isEmpty()) return List.of();
        try {
            return JSON.parseObject(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
