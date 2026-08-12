package com.example.springairagdemo.parser;

import com.example.springairagdemo.config.RagConfigProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题感知切分辅助器：识别页面文本中的标题行，维护标题链（如 "3 考勤制度 &gt; 3.2 请假流程"），
 * 并记录每个标题在页面文本中的字符偏移，供 SemanticSplitter 产出的 chunk 定位所属标题。
 * <p>
 * 识别规则（启发式）：
 * <ol>
 *   <li>数字序号标题：{@code 1. 概述}、{@code 3.2.1 考勤}，深度 = 序号层级；</li>
 *   <li>中文序数标题：{@code 第一章}、{@code 第三节}、{@code 第五条}，深度 = 1/2/3；</li>
 *   <li>无序号短句标题（如 "公司考勤管理办法"）：需包含中文字符、不含标点、长度适中，深度取当前栈顶 + 1。</li>
 * </ol>
 */
@Component
public class HeadingExtractor {

    /** 数字序号：1、1.1、3.2.1，后跟 空格/点/顿号/冒号 等 */
    private static final Pattern NUM_PATTERN = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)\\s*[.、．:：)）]?\\s*");
    /** 中文序数：第一章/篇/部分/节/条 */
    private static final Pattern CN_NUM_PATTERN = Pattern.compile(
            "^\\s*第([一二三四五六七八九十百千万\\d]+)([章节篇部部分条])");
    /** 行尾标点：出现这些结尾说明更像正文句子而非标题 */
    private static final Pattern EOL_PUNCT = Pattern.compile("[。！？；，、,.;:!?]$");
    /** 标题链分隔符 */
    private static final String CHAIN_SEP = " > ";

    /**
     * 从页面文本中提取标题链列表（按偏移升序）
     *
     * @param text 页面文本
     * @param cfg  标题配置
     * @return 标题行列表（含完整链），无标题时返回空列表
     */
    public List<HeadingLine> extract(String text, RagConfigProperties.Heading cfg) {
        List<HeadingLine> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }

        Deque<HeadingLine> stack = new ArrayDeque<>();
        String[] split = text.split("\n", -1);
        int offset = 0;

        for (String rawLine : split) {
            String line = rawLine.strip();
            int lineStart = offset;
            offset += rawLine.length() + 1; // +1 计入换行符

            Integer depth = matchDepth(line, cfg);
            if (depth == null) {
                continue;
            }
            int finalDepth = depth;
            if (depth == -1) {
                // 无序号标题：深度 = 栈顶 + 1（栈空则为 1），上限 maxDepth
                int base = stack.isEmpty() ? 0 : stack.peek().depth();
                finalDepth = Math.min(base + 1, cfg.getMaxDepth());
            }

            // 弹栈：同级或更深层标题出现时，结束旧标题
            while (!stack.isEmpty() && stack.peek().depth() >= finalDepth) {
                stack.pop();
            }
            stack.push(new HeadingLine(lineStart, finalDepth, line, ""));

            // 构建完整链（栈内仅依赖 title/depth，实时计算）
            List<String> chainParts = new ArrayList<>();
            stack.descendingIterator().forEachRemaining(h -> chainParts.add(h.title()));
            String chain = String.join(CHAIN_SEP, chainParts);

            lines.add(new HeadingLine(lineStart, finalDepth, line, chain));
        }
        return lines;
    }

    /**
     * 判断一行是否为标题并返回其层级
     *
     * @return 层级数字（&gt;=1）；{@code -1} 表示无序号标题（深度由调用方决定）；{@code null} 表示不是标题
     */
    private Integer matchDepth(String line, RagConfigProperties.Heading cfg) {
        if (line.isEmpty() || line.length() > cfg.getMaxLength() || line.length() < 2) {
            return null;
        }
        if (EOL_PUNCT.matcher(line).find()) {
            return null;
        }

        Matcher num = NUM_PATTERN.matcher(line);
        if (num.find()) {
            String seq = num.group(1);
            int dots = seq.split("\\.").length - 1;
            return Math.min(dots + 1, cfg.getMaxDepth());
        }

        Matcher cn = CN_NUM_PATTERN.matcher(line);
        if (cn.find()) {
            String unit = cn.group(2);
            int base = switch (unit) {
                case "节" -> 2;
                case "条" -> 3;
                default -> 1; // 章/篇/部/部分
            };
            return Math.min(base, cfg.getMaxDepth());
        }

        // 无序号标题：包含中文字符、不含标点、非纯数字英文
        if (containsCjk(line) && !containsPunct(line)) {
            return -1;
        }
        return null;
    }

    private boolean containsCjk(String s) {
        return s.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    private boolean containsPunct(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("。！？；，、,.;:!?（）()「」『』《》〈〉\"'“”‘’".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标题行记录：offset 为该标题在页面文本中的起始字符偏移
     */
    public record HeadingLine(int offset, int depth, String title, String chain) {
    }
}
