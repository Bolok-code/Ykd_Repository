package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LinkTools {

    @Tool(description = "读取链接内容并返回文本摘要。当用户发送链接、文章、网页时调用此工具")
    public String readLink(
            @ToolParam(description = "要读取的完整网页链接URL") String url) {
        long start = System.currentTimeMillis();
        log.info("[LinkTool] 被调用: url={}", url);
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(15000)
                    .userAgent("Mozilla/5.0")
                    .followRedirects(true)
                    .get();

            String title = doc.title().trim();
            String text = doc.body().text().trim();

            if (text.isEmpty()) {
                return "⛔ 该页面没有可读取的文本内容";
            }

            text = text.length() > 3000 ? text.substring(0, 3000) + "..." : text;
            long elapsed = System.currentTimeMillis() - start;
            log.info("[LinkTool] 读取成功: elapsed={}ms, url={}, title={}, chars={}", elapsed, url, title, text.length());

            String result = title.isEmpty() ? "" : "标题：" + title + "\n\n";
            return result + "以下是链接内容摘要：\n" + text;

        } catch (IllegalArgumentException e) {
            log.error("[LinkTool] URL格式错误: url={}", url, e);
            return "❌ 链接格式无效，请提供完整的网页地址";
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LinkTool] 读取失败: elapsed={}ms, url={}, error={}", elapsed, url, e.getMessage(), e);
            return "❌ 读取链接失败: " + e.getMessage();
        }
    }
}