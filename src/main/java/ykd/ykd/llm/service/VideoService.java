package ykd.ykd.llm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

/**
 * 视频生成服务，封装 Agnes AI 视频 API 的 HTTP 调用。
 *
 * <p>Agnes Video V2.0 是异步任务 API，需要两步完成：</p>
 * <ol>
 *   <li>提交任务：{@link #submitTask(String)} → 返回 taskId</li>
 *   <li>查询状态：{@link #checkStatus(String)} → 返回当前状态及结果 URL</li>
 * </ol>
 */
@Slf4j
@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    public VideoService(@Value("${spring.ai.openai.api-key}") String apiKey,
                        @Value("${spring.ai.openai.base-url}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 提交视频生成任务。
     *
     * @param prompt 视频内容描述
     * @return 任务 ID（taskId），用于后续查询
     */
    public String submitTask(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", "agnes-video-v2.0",
                "prompt", prompt,
                "width", 1152,
                "height", 768,
                "num_frames", 121
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/videos",
                new HttpEntity<>(body, headers),
                String.class
        );

        JsonNode json = objectMapper.readTree(response.getBody());
        String taskId = json.get("id").asText();
        log.info("[VideoService] 提交任务成功: taskId={}, prompt={}", taskId, prompt);
        return taskId;
    }

    /**
     * 查询视频生成任务状态。
     *
     * @param taskId 任务 ID
     * @return API 返回的 JSON 节点，包含 status / output.url 等字段
     */
    public JsonNode checkStatus(String taskId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl + "/videos/" + taskId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        return response.getBody();
    }

    /**
     * 从 URL 下载视频文件。
     *
     * @param videoUrl 视频下载地址
     * @return 视频字节数据，下载失败返回 {@code null}
     */
    public byte[] downloadVideo(String videoUrl) {
        try (InputStream in = URI.create(videoUrl).toURL().openStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            byte[] data = out.toByteArray();
            log.info("[VideoService] 视频下载成功: url={}, size={}KB", videoUrl, data.length / 1024);
            return data;
        } catch (Exception e) {
            log.error("[VideoService] 视频下载失败: url={}", videoUrl, e);
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED, e.getMessage());
        }
    }

}
