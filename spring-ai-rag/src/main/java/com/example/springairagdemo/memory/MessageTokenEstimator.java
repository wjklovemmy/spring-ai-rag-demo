package com.example.springairagdemo.memory;

/**
 * 对话消息 token 估算器（本地近似，不调用任何 API）。
 *
 * <p>规则（保守上界）：
 * <ul>
 *   <li>ASCII 字符（英文/数字/半角标点/空格）：约 4 字符 = 1 token（OpenAI / DeepSeek 通用近似）；</li>
 *   <li>非 ASCII 字符（中文/全角标点等）：1 字符 = 1 token——实际中文在 BPE 分词下通常
 *       &lt; 1 token/字，按 1 计是安全上界，保证窗口不超配；</li>
 * </ul>
 *
 * <p>用途：{@link RedisChatMemory} 滑动窗口按 token 预算裁剪，让"撑爆模型上下文"的因素（token 数）
 * 直接参与窗口计算，而非仅按条数近似；存储消息时把估算结果落库（旧数据缺失时读取端补算）。
 */
public final class MessageTokenEstimator {

    private MessageTokenEstimator() {
    }

    /** 估算一段文本的 token 数（null/空白返回 0） */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int asciiChars = 0;
        int nonAsciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                asciiChars++;
            } else {
                nonAsciiChars++;
            }
        }
        // 非 ASCII 按 1 字符 1 token（中文上界），ASCII 按 4 字符 1 token，向上取整
        return nonAsciiChars + (asciiChars + 3) / 4;
    }
}
