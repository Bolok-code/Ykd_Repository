package ykd.ykd.websearch.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.websearch.config.WebSearchProperties;
import ykd.ykd.websearch.dto.WebSearchResult;
import ykd.ykd.websearch.service.WebSearchService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DoubaoWebSearchServiceImpl implements WebSearchService {

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DoubaoWebSearchServiceImpl(
            WebSearchProperties properties,
            ObjectMapper objectMapper) {

        this.properties = properties;
        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public List<WebSearchResult> search(String query) {

        if (!properties.isEnabled()) {
            throw new BusinessException(
                    ErrorCode.WEB_SEARCH_FAILED,
                    "联网搜索功能尚未启用"
            );
        }

        if (query == null || query.isBlank()) {
            throw new BusinessException(
                    ErrorCode.WEB_SEARCH_FAILED,
                    "搜索内容不能为空"
            );
        }

        if (properties.getApiKey() == null
                || properties.getApiKey().isBlank()) {

            throw new BusinessException(
                    ErrorCode.WEB_SEARCH_FAILED,
                    "联网搜索 API Key 未配置"
            );
        }

        long start = System.currentTimeMillis();

        try {
            int count = Math.max(
                    1,
                    Math.min(properties.getMaxResults(), 10)
            );

            Map<String, Object> filter =
                    new LinkedHashMap<>();

            filter.put("NeedContent", false);
            filter.put("NeedUrl", true);

            Map<String, Object> requestBody =
                    new LinkedHashMap<>();

            requestBody.put("Query", query.trim());
            requestBody.put("SearchType", "web");
            requestBody.put("Count", count);
            requestBody.put("Filter", filter);

            String jsonBody =
                    objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(
                            properties.getTimeoutSeconds()))
                    .header(
                            "Authorization",
                            "Bearer " + properties.getApiKey()
                    )
                    .header(
                            "Content-Type",
                            "application/json"
                    )
                    .POST(HttpRequest.BodyPublishers.ofString(
                            jsonBody,
                            StandardCharsets.UTF_8
                    ))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                log.warn(
                        "[WebSearch] HTTP请求失败: status={}",
                        response.statusCode()
                );

                throw new BusinessException(
                        ErrorCode.WEB_SEARCH_FAILED,
                        "联网搜索请求失败，HTTP状态码："
                                + response.statusCode()
                );
            }

            JsonNode root =
                    objectMapper.readTree(response.body());

            checkApiError(root);

            List<WebSearchResult> results =
                    parseResults(root, count);

            long elapsed =
                    System.currentTimeMillis() - start;

            log.info(
                    "[WebSearch] 搜索完成: query={}, count={}, elapsed={}ms",
                    query,
                    results.size(),
                    elapsed
            );

            return results;

        } catch (BusinessException e) {
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new BusinessException(
                    ErrorCode.WEB_SEARCH_FAILED,
                    "联网搜索请求被中断"
            );

        } catch (Exception e) {
            long elapsed =
                    System.currentTimeMillis() - start;

            log.error(
                    "[WebSearch] 搜索异常: query={}, elapsed={}ms, error={}",
                    query,
                    elapsed,
                    e.getMessage(),
                    e
            );

            throw new BusinessException(
                    ErrorCode.WEB_SEARCH_FAILED,
                    "联网搜索暂时不可用"
            );
        }
    }

    private void checkApiError(JsonNode root) {

        JsonNode metadata =
                root.path("ResponseMetadata");

        JsonNode error =
                metadata.path("Error");

        if (!error.isMissingNode()
                && !error.isNull()) {

            String code =
                    error.path("Code").asText("");

            String message =
                    error.path("Message").asText("");

            String requestId =
                    metadata.path("RequestId").asText("");

            log.warn(
                    "[WebSearch] 豆包接口返回错误: code={}, message={}, requestId={}",
                    code,
                    message,
                    requestId
            );

            throw new BusinessException(
                    ErrorCode.WEB_SEARCH_FAILED,
                    "联网搜索失败：" + message
            );
        }
    }

    private List<WebSearchResult> parseResults(
            JsonNode root,
            int maxResults) {

        JsonNode webResults =
                root.path("Result")
                        .path("WebResults");

        if (!webResults.isArray()) {
            return List.of();
        }

        List<WebSearchResult> results =
                new ArrayList<>();

        int count = Math.min(
                webResults.size(),
                maxResults
        );

        for (int i = 0; i < count; i++) {

            JsonNode item =
                    webResults.get(i);

            results.add(new WebSearchResult(
                    item.path("Title").asText(""),
                    item.path("Url").asText(""),
                    item.path("Snippet").asText(""),
                    item.path("Summary").asText(""),
                    item.path("SiteName").asText(""),
                    item.path("PublishTime").asText("")
            ));
        }

        return results;
    }
}