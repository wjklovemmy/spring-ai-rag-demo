package com.example.springairagdemo.parser;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OCR 文本与文本层合并工具：按行去重，只保留文本层没有的新增内容。
 * <p>
 * 场景：整页/整图渲染后识别会把正文也识别出来，与文档文本层大量重复；
 * 通过归一化行匹配（含"一方包含另一方"的模糊匹配，防 OCR 截断/合并导致的重复）
 * 剔除重复行，仅把图片新增内容追加到文本层末尾。
 * <p>
 * PDF 混合页（文本层 + 图片）与 Word 内嵌图片共用同一套合并规则。
 */
@Slf4j
public final class OcrTextMerger {

    private OcrTextMerger() {
    }

    /**
     * 合并文本层与 OCR 结果
     *
     * @param textLayer 文档文本层（可能为空）
     * @param ocrText   OCR 识别结果
     * @return 合并后的文本；若 OCR 未带来新增内容（装饰图/logo），原样返回文本层
     */
    public static String merge(String textLayer, String ocrText) {
        List<String> layerLines = new ArrayList<>();
        for (String line : textLayer.split("\\R")) {
            String norm = normalize(line);
            if (!norm.isEmpty()) {
                layerLines.add(norm);
            }
        }
        Set<String> layerSet = new HashSet<>(layerLines);

        StringBuilder sb = new StringBuilder(textLayer);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        int added = 0;
        for (String line : ocrText.split("\\R")) {
            String norm = normalize(line);
            if (norm.isEmpty() || layerSet.contains(norm)) {
                continue;
            }
            // 防 OCR 截断/合并导致的重复：双方足够长且互相包含，视为同一内容
            boolean dup = false;
            for (String l : layerLines) {
                if (l.length() >= 4 && norm.length() >= 4
                        && (l.contains(norm) || norm.contains(l))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                sb.append(line.trim()).append('\n');
                added++;
            }
        }
        if (added == 0) {
            // 图片内无新增文字（装饰图/logo），保持原文本层，避免噪声污染
            return textLayer;
        }
        log.debug("OCR 合并：追加新增 {} 行", added);
        return sb.toString();
    }

    /** OCR 行归一化：去首尾空白、压缩连续空白，用于文本层与 OCR 结果的重复匹配 */
    public static String normalize(String line) {
        return line.trim().replaceAll("[\\s\u00A0]+", " ");
    }
}
