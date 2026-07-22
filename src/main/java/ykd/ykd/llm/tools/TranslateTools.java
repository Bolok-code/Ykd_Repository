package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TranslateTools {

    private final ChatClient translateClient;

    public TranslateTools(DeepSeekChatModel deepSeekChatModel) {
        this.translateClient = ChatClient.builder(deepSeekChatModel)
                .defaultSystem("你是一个专业翻译助手。规则：\n" +
                        "1. 如果输入是中文，翻译成英文\n" +
                        "2. 如果输入是英文，翻译成中文\n" +
                        "3. 只输出翻译结果，不要任何解释、注释或额外文字\n" +
                        "4. 翻译要自然、地道，符合日常聊天用语习惯\n" +
                        "5. 保持原文的语气和风格")
                .build();
    }

    @Tool(description = "翻译文本。当用户要求翻译、翻译这句话用XX怎么说、translate时调用此工具。自动检测语言方向：中文翻译成英文，英文翻译成中文")
    public String translate(
            @ToolParam(description = "要翻译的文本内容") String text,
            @ToolParam(description = "目标语言代码：auto=自动检测, en=翻译成英文, zh=翻译成中文。默认auto", required = false) String targetLang) {
        long start = System.currentTimeMillis();
        log.info("[TranslateTool] 被调用: text={}, targetLang={}", text, targetLang);
        try {
            if (text == null || text.isBlank()) {
                return "❌ 请输入要翻译的内容";
            }
            String langInstruction = buildLangInstruction(targetLang, text);
            String result = translateClient.prompt()
                    .user(langInstruction + text)
                    .call()
                    .content();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[TranslateTool] 翻译完成: elapsed={}ms, text={}, result={}", elapsed, text, result);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[TranslateTool] 翻译失败: elapsed={}ms, text={}, error={}", elapsed, text, e.getMessage(), e);
            return "❌ 翻译失败，请稍后重试";
        }
    }

    private String buildLangInstruction(String targetLang, String text) {
        if (targetLang == null || targetLang.isBlank() || "auto".equalsIgnoreCase(targetLang)) {
            boolean hasChinese = text.matches(".*[\\u4e00-\\u9fa5].*");
            return hasChinese ? "请将以下内容翻译成英文：\n" : "请将以下内容翻译成中文：\n";
        }
        if ("en".equalsIgnoreCase(targetLang) || "english".equalsIgnoreCase(targetLang)) {
            return "请将以下内容翻译成英文：\n";
        }
        if ("zh".equalsIgnoreCase(targetLang) || "chinese".equalsIgnoreCase(targetLang)) {
            return "请将以下内容翻译成中文：\n";
        }
        return "请将以下内容翻译成" + targetLang + "：\n";
    }
}
