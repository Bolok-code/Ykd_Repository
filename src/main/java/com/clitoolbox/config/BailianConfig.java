package com.clitoolbox.config;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已校验的百炼配置。API Key、业务空间和地域必须属于同一个百炼地域。
 */
public record BailianConfig(
        String apiKey,
        String workspaceId,
        String region,
        String visionModel,
        String imageModel,
        String imageSize,
        Duration timeout) {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern SAFE_REGION = Pattern.compile("[a-z0-9-]+");
    private static final Pattern PIXEL_SIZE = Pattern.compile("(\\d{3,4})\\*(\\d{3,4})");
    private static final long QWEN_IMAGE_MIN_PIXELS = 512L * 512L;
    private static final long QWEN_IMAGE_MAX_PIXELS = 2048L * 2048L;
    private static final Set<String> QWEN_IMAGE_PLUS_SIZES = Set.of(
            "1664*928",
            "1472*1104",
            "1328*1328",
            "1104*1472",
            "928*1664");

    public BailianConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼 API Key 未配置，请设置 DASHSCOPE_API_KEY，"
                            + "或在 config/application-local.yml 中填写 app.bailian.api-key。");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼业务空间 ID 未配置，请设置 BAILIAN_WORKSPACE_ID，"
                            + "或在 config/application-local.yml 中填写 app.bailian.workspace-id。");
        }
        if (!SAFE_IDENTIFIER.matcher(workspaceId.trim()).matches()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼业务空间 ID 格式不正确。");
        }
        if (region == null || region.isBlank()
                || !SAFE_REGION.matcher(region.trim().toLowerCase(Locale.ROOT)).matches()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼地域格式不正确。");
        }
        if (visionModel == null || visionModel.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼视觉模型不能为空。");
        }
        if (imageModel == null || imageModel.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼生图模型不能为空。");
        }
        String normalizedSize = normalizeAndValidateImageSize(
                imageModel.trim(),
                imageSize);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼请求超时时间必须大于 0。");
        }

        apiKey = apiKey.trim();
        workspaceId = workspaceId.trim();
        region = region.trim().toLowerCase(Locale.ROOT);
        visionModel = visionModel.trim();
        imageModel = imageModel.trim();
        imageSize = normalizedSize;
    }

    public URI compatibleBaseUrl() {
        return URI.create(endpointRoot() + "/compatible-mode/v1");
    }

    public URI dashScopeBaseUrl() {
        return URI.create(endpointRoot() + "/api/v1");
    }

    private String endpointRoot() {
        return "https://" + workspaceId + "." + region + ".maas.aliyuncs.com";
    }

    private static String normalizeAndValidateImageSize(
            String imageModel,
            String imageSize) {
        String normalized = imageSize == null
                ? ""
                : imageSize.trim().toLowerCase(Locale.ROOT).replace('x', '*');
        Matcher matcher = PIXEL_SIZE.matcher(normalized);
        if (!matcher.matches()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼图片尺寸必须使用“宽*高”格式，例如 1024*1024。");
        }
        if (imageModel.startsWith("qwen-image-plus")
                && !QWEN_IMAGE_PLUS_SIZES.contains(normalized)) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "qwen-image-plus 仅支持 1664*928、1472*1104、1328*1328、"
                            + "1104*1472 或 928*1664。");
        }
        if (imageModel.startsWith("qwen-image-2.0")) {
            long width = Long.parseLong(matcher.group(1));
            long height = Long.parseLong(matcher.group(2));
            long pixels = width * height;
            if (pixels < QWEN_IMAGE_MIN_PIXELS || pixels > QWEN_IMAGE_MAX_PIXELS) {
                throw new CliException(
                        ErrorCode.CONFIG_ERROR,
                        "qwen-image-2.0 输出总像素必须在 512*512 到 2048*2048 之间。");
            }
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "BailianConfig[apiKey=***, workspaceId=" + workspaceId
                + ", region=" + region
                + ", visionModel=" + visionModel
                + ", imageModel=" + imageModel
                + ", imageSize=" + imageSize
                + ", timeout=" + timeout + "]";
    }
}
