package com.clitoolbox.ai.speech;

import com.clitoolbox.config.SilkEncoderConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 通过项目内 Node.js + silk-wasm 工具将 PCM 转换为微信 SILK v3。
 */
public final class NodeSilkSpeechEncoder implements SpeechEncoder {
    static final int SILK_ENCODE_TYPE = 6;
    static final int SILK_BITS_PER_SAMPLE = 16;
    private static final byte[] SILK_HEADER =
            "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_DIAGNOSTIC_CHARS = 1_000;

    private final SilkEncoderConfig config;
    private final ObjectMapper objectMapper;

    public NodeSilkSpeechEncoder(
            SilkEncoderConfig config,
            ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public GeneratedSpeech encode(GeneratedSpeech source) {
        PcmAudio.validate(source);
        validateRuntimeFiles();

        Path inputPath = null;
        Path outputPath = null;
        try {
            Files.createDirectories(config.workDirectory());
            inputPath = Files.createTempFile(
                    config.workDirectory(),
                    "speech-",
                    ".pcm");
            outputPath = Files.createTempFile(
                    config.workDirectory(),
                    "speech-",
                    ".silk");
            Files.write(inputPath, source.data());

            Process process = new ProcessBuilder(
                    config.nodeCommand(),
                    config.scriptPath().toString(),
                    inputPath.toString(),
                    outputPath.toString(),
                    Integer.toString(source.sampleRate()))
                    .redirectErrorStream(true)
                    .start();

            boolean completed = process.waitFor(
                    config.timeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new CliException(
                        ErrorCode.NETWORK_ERROR,
                        "SILK 编码超时，请检查 Node.js 和 silk-wasm。");
            }

            String processOutput = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new CliException(
                        ErrorCode.CONFIG_ERROR,
                        "SILK 编码失败：" + normalizeDiagnostic(processOutput));
            }

            byte[] silk = Files.readAllBytes(outputPath);
            validateSilk(silk);
            JsonNode metadata = parseMetadata(processOutput);
            int durationMs = metadata.path("durationMs").asInt(0);
            int sampleRate = metadata.path("sampleRate").asInt(0);
            if (durationMs <= 0 || sampleRate != source.sampleRate()) {
                throw new CliException(
                        ErrorCode.UNKNOWN,
                        "SILK 编码器返回的时长或采样率不正确。");
            }

            return new GeneratedSpeech(
                    silk,
                    "bailian-reply.silk",
                    durationMs,
                    sampleRate,
                    SILK_ENCODE_TYPE,
                    SILK_BITS_PER_SAMPLE);
        } catch (CliException e) {
            throw e;
        } catch (IOException e) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "无法运行 SILK 编码器，请确认 Node.js 可用并执行 "
                            + installCommand(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "SILK 编码过程被中断。",
                    e);
        } finally {
            deleteQuietly(inputPath);
            deleteQuietly(outputPath);
        }
    }

    private void validateRuntimeFiles() {
        if (!Files.isRegularFile(config.scriptPath())) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "未找到 SILK 编码脚本：" + config.scriptPath());
        }
        Path packageDirectory = config.scriptPath().getParent();
        if (packageDirectory == null
                || !Files.isDirectory(
                        packageDirectory.resolve("node_modules").resolve("silk-wasm"))) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "尚未安装 silk-wasm，请执行 "
                            + installCommand());
        }
    }

    static void validateSilk(byte[] data) {
        boolean headerAtStart = startsWith(data, 0, SILK_HEADER);
        boolean tencentHeader = data != null
                && data.length > SILK_HEADER.length
                && data[0] == 0x02
                && startsWith(data, 1, SILK_HEADER);
        if (!headerAtStart && !tencentHeader) {
            throw new CliException(
                    ErrorCode.UNKNOWN,
                    "SILK 编码结果缺少 #!SILK_V3 文件头。");
        }
    }

    private JsonNode parseMetadata(String output) {
        if (output == null || output.isBlank()) {
            throw new CliException(ErrorCode.UNKNOWN, "SILK 编码器没有返回元数据。");
        }
        String[] lines = output.split("\\R");
        String json = lines[lines.length - 1].trim();
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new CliException(
                    ErrorCode.UNKNOWN,
                    "无法解析 SILK 编码结果：" + normalizeDiagnostic(output),
                    e);
        }
    }

    private static boolean startsWith(byte[] data, int offset, byte[] expected) {
        return data != null
                && offset >= 0
                && data.length >= offset + expected.length
                && Arrays.equals(
                        data,
                        offset,
                        offset + expected.length,
                        expected,
                        0,
                        expected.length);
    }

    private static String normalizeDiagnostic(String output) {
        if (output == null || output.isBlank()) {
            return "没有错误输出";
        }
        String normalized = output.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_DIAGNOSTIC_CHARS
                ? normalized
                : normalized.substring(0, MAX_DIAGNOSTIC_CHARS) + "...";
    }

    private static String installCommand() {
        return "`npm --prefix tools/silk-encoder install "
                + "--cache tools/silk-encoder/.npm-cache`。";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不覆盖主要编码结果或异常。
        }
    }
}
