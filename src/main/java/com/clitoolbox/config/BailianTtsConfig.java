package com.clitoolbox.config;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.time.Duration;
import java.util.Set;

/**
 * 已校验的百炼非实时语音合成配置。
 */
public record BailianTtsConfig(
        String model,
        String voice,
        int sampleRate,
        Duration timeout) {
    private static final Set<Integer> SUPPORTED_SAMPLE_RATES =
            Set.of(8_000, 16_000, 22_050, 24_000, 44_100, 48_000);

    public BailianTtsConfig {
        if (model == null || model.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼语音合成模型不能为空。");
        }
        if (voice == null || voice.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼语音合成音色不能为空。");
        }
        if (!SUPPORTED_SAMPLE_RATES.contains(sampleRate)) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼语音采样率必须是 8000、16000、22050、24000、44100 或 48000。");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "百炼语音请求超时时间必须大于 0。");
        }

        model = model.trim();
        voice = voice.trim();
    }
}
