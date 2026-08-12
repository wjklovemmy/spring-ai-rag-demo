package com.example.springairagdemo.service;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.teaopenapi.models.Config;
import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阿里云 OCR（文字识别）实现
 *
 * <p>使用官方 SDK ocr-api20210707 调用 RecognizeAllText 通用文字识别接口。
 * 需要开通阿里云 OCR 服务并配置 AccessKey（环境变量 ALIYUN_OCR_AK / ALIYUN_OCR_SK）。
 */
@Slf4j
@Service
public class AliyunOcrService implements OcrService {

    private final RagConfigProperties config;
    private volatile Client client;

    public AliyunOcrService(RagConfigProperties config) {
        this.config = config;
    }

    @Override
    public boolean isEnabled() {
        return config.getOcr().isEnabled();
    }

    private Client getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    RagConfigProperties.Ocr ocr = config.getOcr();
                    Config cfg = new Config()
                            .setAccessKeyId(ocr.getAccessKeyId())
                            .setAccessKeySecret(ocr.getAccessKeySecret())
                            .setEndpoint("ocr-api." + ocr.getRegionId() + ".aliyuncs.com");
                    try {
                        this.client = new Client(cfg);
                    } catch (Exception e) {
                        throw new IllegalStateException("初始化阿里云 OCR 客户端失败", e);
                    }
                }
            }
        }
        return client;
    }

    @Override
    public String recognizeImage(byte[] imageBytes) {
        try {
            RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                    .setBody(new java.io.ByteArrayInputStream(imageBytes))
                    .setType("general");
            RecognizeAllTextResponse response = getClient().recognizeAllText(request);
            if (response == null || response.getBody() == null || response.getBody().getData() == null) {
                log.warn("阿里云 OCR 返回空响应");
                return "";
            }
            String content = response.getBody().getData().getContent();
            return content == null ? "" : content;
        } catch (Exception e) {
            if (config.getOcr().isFailOnError()) {
                throw new RuntimeException("阿里云 OCR 调用失败", e);
            }
            log.error("阿里云 OCR 调用失败，已跳过该页: {}", e.getMessage());
            return "";
        }
    }
}
