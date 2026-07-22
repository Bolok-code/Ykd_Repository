package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class CalculatorTools {

    @Tool(description = "计算数学表达式。当用户要求计算、算数、加减乘除、数学运算时调用此工具")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '1+2*3'、'(10+5)/3'") String expression) {
        long start = System.currentTimeMillis();
        log.info("[CalcTool] 被调用: expression={}", expression);
        try {
            if (expression == null || expression.isBlank()) {
                return "❌ 请输入要计算的数学表达式";
            }
            String cleaned = expression.replaceAll("\\s+", "")
                    .replaceAll("×", "*")
                    .replaceAll("÷", "/");
            Parser parser = new Parser(cleaned);
            double result = parser.parseExpression();
            if (parser.pos < parser.input.length()) {
                return "❌ 表达式格式错误，多余字符: " + cleaned.substring(parser.pos);
            }
            String output = formatResult(result);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[CalcTool] 计算完成: elapsed={}ms, expression={}, result={}", elapsed, expression, output);
            return expression + " = " + output;
        } catch (ArithmeticException e) {
            log.warn("[CalcTool] 算术错误: expression={}, error={}", expression, e.getMessage());
            return "❌ 计算错误: " + e.getMessage();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[CalcTool] 计算失败: elapsed={}ms, expression={}, error={}", elapsed, expression, e.getMessage());
            return "❌ 无法计算该表达式，请检查格式是否正确";
        }
    }

    private String formatResult(double result) {
        if (result == (long) result && !Double.isInfinite(result)) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    private static class Parser {
        final String input;
        int pos = 0;

        Parser(String input) {
            this.input = input;
        }

        double parseExpression() {
            double result = parseTerm();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '+') {
                    pos++;
                    result += parseTerm();
                } else if (c == '-') {
                    pos++;
                    result -= parseTerm();
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseTerm() {
            double result = parseFactor();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '*') {
                    pos++;
                    result *= parseFactor();
                } else if (c == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) throw new ArithmeticException("除数不能为零");
                    result /= divisor;
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseFactor() {
            if (pos >= input.length()) throw new IllegalArgumentException("表达式不完整");
            char c = input.charAt(pos);
            if (c == '+') {
                pos++;
                return parseFactor();
            }
            if (c == '-') {
                pos++;
                return -parseFactor();
            }
            if (c == '(') {
                pos++;
                double result = parseExpression();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                pos++;
                return result;
            }
            return parseNumber();
        }

        private double parseNumber() {
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("无法解析的数字");
            }
            return Double.parseDouble(input.substring(start, pos));
        }
    }
}
