package com.example.springairagdemo.service;

/**
 * OCR（光学字符识别）服务
 *
 * <p>用于扫描版 PDF：当 PDF 没有可提取的文本层时，将页面渲染为图片后识别文字。
 */
public interface OcrService {

    /**
     * 是否启用 OCR
     */
    boolean isEnabled();

    /**
     * 识别图片中的文字
     *
     * @param imageBytes 图片字节（PNG/JPEG）
     * @return 识别出的纯文本
     */
    String recognizeImage(byte[] imageBytes);
}
