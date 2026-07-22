package ykd.ykd.llm.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

@Component
public class LinkTools {
    private static final Logger log = LoggerFactory.getLogger(LinkTools.class);

    @Tool(description = "读取链接内容并返回文本摘要。当用户发送链接、文章、网页时调用此工具")
    public String readLink(
            @ToolParam(description = "要读取的完整网页链接URL") String url) {
        long start = System.currentTimeMillis();
        log.info("[LinkTool] 被调用: url={}", url);
        try {
            URI uri = URI.create(url);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                return "❌ 访问链接失败，HTTP状态码: " + code;
            }
            String contentType = conn.getContentType();
            if (contentType != null && !contentType.startsWith("text/html") && !contentType.startsWith("text/plain")) {
                return "⛔ 链接内容类型为 " + contentType + "，暂不支持读取";
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            int totalRead = 0;
            while ((line = reader.readLine()) != null && totalRead < 50000) {
                // 简单去除HTML标签
                String clean = line.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty()) {
                    sb.append(clean).append("\n");
                    totalRead += clean.length();
                }
            }
            reader.close();

            String content = sb.length() > 3000 ? sb.substring(0, 3000) + "..." : sb.toString();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[LinkTool] 读取成功: elapsed={}ms, url={}, chars={}", elapsed, url, content.length());
            return "以下是链接内容摘要：\n" + content;

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