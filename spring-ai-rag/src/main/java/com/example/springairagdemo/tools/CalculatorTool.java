package com.example.springairagdemo.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 通用计算工具（Spring AI Tool Calling / Function Calling）。
 *
 * <p>背景：年假余额、到手工资、差额/合计、百分比换算等"需要数值运算"的问题，
 * 模型直接心算容易出错，尤其涉及多步运算时。本工具让模型把自然语言中的数值
 * 翻译成数学表达式，由服务端用受限的表达式求值器安全计算，避免模型手算出错。
 *
 * <p>安全设计：表达式求值器为自研递归下降解析器，仅支持数字与 {@code + - * / ( ) ^ %}
 * 运算（无变量、无函数、无脚本引擎），杜绝任意代码执行（RCE）；支持全角/中文符号
 * 归一化、中文单位剔除，并对非法输入返回明确的纠错提示供模型修正后重试。
 */
@Slf4j
@Component
public class CalculatorTool {

    /** 工具名（SSE 事件/轨迹落库展示用） */
    public static final String TOOL_NAME = "calculate";

    /** 允许出现在表达式中的 ASCII 字符集合 */
    private static final String ALLOWED_ASCII = "+-*/()^%.0123456789";

    @Tool(description = "计算数学表达式并返回精确结果。"
            + "当用户的问题需要进行数值运算时调用，例如：年假/假期余额（总天数 - 已用天数）、"
            + "金额计算（单价 * 数量、含税/扣款百分比）、差值、合计、平均数、百分比换算、幂运算等。"
            + "将自然语言中的数值翻译为数学表达式：只支持数字（含小数）、+ - * / 括号、"
            + "^（幂，如 2^3 表示 2 的 3 次方）、%（百分比后缀，如 50% 表示 0.5），"
            + "不支持变量、函数或单位（如『5天-2天』应写成 5-2）。"
            + "示例：每年 5 天年假已休 2 天 → 5-2；工资 8000 元扣社保 10% → 8000*(1-10%)。")
    public String calculate(
            @ToolParam(description = "要计算的数学表达式，只含数字与运算符，"
                    + "如 5-2、8000*(1-10%)、2^3；百分比用 % 后缀表示（10% = 0.1）") String expression,
            ToolContext toolContext) {
        String normalized;
        String result;
        try {
            normalized = normalize(expression);
            double value = new Parser(normalized).parse();
            result = normalized + " = " + format(value);
        } catch (IllegalArgumentException e) {
            String error = "无法计算：" + e.getMessage()
                    + "。请将问题整理为仅含数字和 + - * / ( ) ^ % 的数学表达式后重新调用本工具";
            emitToolEvent(toolContext, KbQueryTools.ToolEvent.STATUS_ERROR, expression, error);
            return error;
        }
        emitToolEvent(toolContext, KbQueryTools.ToolEvent.STATUS_DONE, normalized, result);
        return result;
    }

    /** 发布工具调用事件（SSE 展示用），与 KbQueryTools 共用 Sink；未注入 Sink 时静默跳过 */
    private void emitToolEvent(ToolContext toolContext, String status, String args, String result) {
        if (toolContext == null) return;
        Object sinkObj = toolContext.getContext().get(KbQueryTools.TOOL_EVENT_SINK_KEY);
        if (!(sinkObj instanceof Sinks.Many<?> sink)) return;
        @SuppressWarnings("unchecked")
        Sinks.Many<KbQueryTools.ToolEvent> typed = (Sinks.Many<KbQueryTools.ToolEvent>) sink;
        typed.tryEmitNext(new KbQueryTools.ToolEvent(TOOL_NAME, status, truncate(args), truncate(result)));
    }

    /** 事件摘要截断，避免超大参数/结果撑爆 SSE 帧 */
    private static String truncate(String s) {
        if (s == null || s.length() <= 200) return s;
        return s.substring(0, 200) + "...(截断)";
    }

    /**
     * 表达式归一化：全角/中文符号转半角、中文单位剔除、空格去除，
     * 非法字符（如字母/函数调用）直接报错，避免静默算错。
     */
    private static String normalize(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式为空");
        }
        StringBuilder sb = new StringBuilder(expression.length());
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            // 全角转半角
            if (c >= '０' && c <= '９') {
                c = (char) (c - '０' + '0');
            } else if (c == '．') {
                c = '.';
            } else {
                switch (c) {
                    case '＋' -> c = '+';
                    case '－', '−' -> c = '-';
                    case '×', '＊' -> c = '*';
                    case '÷' -> c = '/';
                    case '（' -> c = '(';
                    case '）' -> c = ')';
                    case '％' -> c = '%';
                    default -> {
                        // 跳过空格（含全角空格）
                        if (c == ' ' || c == '\u3000') continue;
                        // 剔除中文（单位词/描述词，如『5天-2天』中的『天』）
                        if (c >= '\u4e00' && c <= '\u9fff') continue;
                        // 中文标点（，。！？；：、等）视为噪声剔除
                        if (c > '\u007e') continue;
                        // ASCII 字母/其他字符：明确报错，防止把 sqrt(9) 静默算成 9
                        if (ALLOWED_ASCII.indexOf(c) < 0) {
                            throw new IllegalArgumentException("存在不支持的字符 '" + c + "'（仅支持数字和 + - * / ( ) ^ %）");
                        }
                    }
                }
            }
            sb.append(c);
        }
        if (sb.isEmpty()) {
            throw new IllegalArgumentException("表达式为空");
        }
        return sb.toString();
    }

    /** 结果格式化：整数优先；小数保留最多 6 位并去除尾随 0 */
    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("计算结果超出范围");
        }
        if (value == 0 || Math.abs(value - Math.round(value)) < 1e-9) {
            return String.valueOf((long) Math.round(value));
        }
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 受限表达式求值器（递归下降）：
     * <pre>
     * expr  := term (('+'|'-') term)*
     * term  := pow (('*'|'/') pow)*
     * pow   := unary ('^' pow)?          // 幂右结合
     * unary := ('-'|'+') unary | primary
     * primary := number | '(' expr ')'
     * </pre>
     * 支持一元正负号、括号、百分比后缀（50% = 0.5），除零/括号不匹配/非法数字均抛出明确错误。
     */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            if (pos < s.length()) {
                throw new IllegalArgumentException("存在多余内容 '" + s.substring(pos) + "'");
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') {
                    pos++;
                    v += term();
                } else if (c == '-') {
                    pos++;
                    v -= term();
                } else {
                    break;
                }
            }
            return v;
        }

        private double term() {
            double v = pow();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') {
                    pos++;
                    v *= pow();
                } else if (c == '/') {
                    pos++;
                    double d = pow();
                    if (d == 0) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    v /= d;
                } else {
                    break;
                }
            }
            return v;
        }

        private double pow() {
            double base = unary();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                return Math.pow(base, pow());
            }
            return base;
        }

        private double unary() {
            if (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') {
                    pos++;
                    return unary();
                }
                if (c == '-') {
                    pos++;
                    return -unary();
                }
            }
            return primary();
        }

        private double primary() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("表达式不完整");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = expr();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new IllegalArgumentException("括号不匹配");
                }
                pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                return number();
            }
            throw new IllegalArgumentException("存在不支持的字符 '" + c + "'");
        }

        private double number() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            String numStr = s.substring(start, pos);
            double v;
            try {
                v = Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数字格式错误 '" + numStr + "'");
            }
            // 百分比后缀：50% = 0.5
            if (pos < s.length() && s.charAt(pos) == '%') {
                pos++;
                v /= 100.0;
            }
            return v;
        }
    }
}
