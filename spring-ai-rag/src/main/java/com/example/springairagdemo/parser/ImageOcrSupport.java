package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 文档内嵌图片 OCR 支持：Office 文档（Word / Excel）解析器共用同一套「过滤 → 编码 → 识别」逻辑。
 * <p>
 * 过滤规则：不可解码的图片（emf/wmf 矢量图、异常格式）跳过；短边小于
 * {@code rag.ocr.word-min-image-side} 的图片（图标/装饰线/logo）跳过。
 * 编码规则：jpg/png/bmp 直接用原始字节（避免大图转 PNG 体积膨胀、超出 OCR 请求体上限），
 * 其余格式（gif/tiff 等）统一转 PNG；长边超过 {@link #MAX_IMAGE_SIDE} 时等比缩小。
 * <p>
 * 调用方负责「单篇张数限流」与「图片内容去重」：本类只处理单张图片，
 * 不持有文档级状态（解析器是单例 Bean）。
 */
@Component
@Slf4j
public class ImageOcrSupport {

    /** OCR 可直接识别的图片格式：直接用原始字节送识别，避免大图转 PNG 体积膨胀（阿里云 OCR 有请求体上限） */
    private static final Set<String> RAW_FORMATS = Set.of("jpg", "jpeg", "png", "bmp");

    /** 送 OCR 前图片长边上限（像素）：超过则等比缩小，避免请求体过大与识别变慢 */
    private static final int MAX_IMAGE_SIDE = 2400;

    private final RagConfigProperties config;
    private final OcrService ocrService;

    public ImageOcrSupport(RagConfigProperties config, OcrService ocrService) {
        this.config = config;
        this.ocrService = ocrService;
    }

    /** OCR 是否可用（开关关闭或未配置 AK/SK 时为 false，调用方应跳过整段图片处理） */
    public boolean isEnabled() {
        return ocrService.isEnabled();
    }

    /** 图片内容指纹（MD5）：同一张图在文档中重复出现时只识别一次，避免重复消耗 OCR */
    public static String fingerprint(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            // 理论上不会发生（MD5 为 JDK 必备算法），退化为"长度 + 内容散列"
            return data.length + "-" + java.util.Arrays.hashCode(data);
        }
    }

    /**
     * 单张图片 OCR
     *
     * @param data      原始图片字节
     * @param extension 原始扩展名（小写、无点），用于判断是否可直接送 OCR
     * @return 识别文字；图片被过滤（图标/矢量图）、未识别出文字或识别失败时返回 null
     */
    public String recognize(byte[] data, String extension) {
        try {
            byte[] payload = toPayload(data, extension);
            if (payload == null) {
                return null;
            }
            String text = ocrService.recognizeImage(payload);
            if (text == null || text.isBlank()) {
                return null;
            }
            log.debug("图片 OCR 成功：原始 {} 字节/{}，送识别 {} 字节，识别 {} 字符",
                    data.length, extension, payload.length, text.length());
            return text;
        } catch (Exception e) {
            // 单图失败不影响整篇解析（与 PDF 单页失败跳过一致）
            log.warn("图片 OCR 失败，已跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成送 OCR 的图片字节（含尺寸过滤与格式转换）
     *
     * @return 图片字节；应跳过时返回 null
     */
    private byte[] toPayload(byte[] data, String extension) throws IOException {
        BufferedImage decoded;
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            decoded = ImageIO.read(in);
        }
        if (decoded == null) {
            log.debug("跳过不可解码图片（可能为 emf/wmf 矢量图）：{} 字节", data.length);
            return null;
        }

        int minSide = Math.min(decoded.getWidth(), decoded.getHeight());
        if (minSide < config.getOcr().getWordMinImageSide()) {
            log.debug("跳过过小图片（{}x{}，视为图标/装饰）", decoded.getWidth(), decoded.getHeight());
            return null;
        }

        boolean oversize = Math.max(decoded.getWidth(), decoded.getHeight()) > MAX_IMAGE_SIDE;
        if (!oversize && extension != null && RAW_FORMATS.contains(extension)) {
            return data;
        }
        return toPngBytes(oversize ? scaleDown(decoded) : decoded);
    }

    /** 等比缩小到长边不超过 {@link #MAX_IMAGE_SIDE} */
    private BufferedImage scaleDown(BufferedImage source) {
        double ratio = (double) MAX_IMAGE_SIDE / Math.max(source.getWidth(), source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
