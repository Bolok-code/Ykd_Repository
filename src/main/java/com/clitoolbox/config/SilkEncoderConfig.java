package com.clitoolbox.config;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 已校验的 Node.js SILK 编码器配置。
 */
public record SilkEncoderConfig(
        String nodeCommand,
        Path scriptPath,
        Path workDirectory,
        Duration timeout) {

    public SilkEncoderConfig {
        if (nodeCommand == null || nodeCommand.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "SILK 编码器的 Node 命令不能为空。");
        }
        if (scriptPath == null) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "SILK 编码脚本路径不能为空。");
        }
        if (workDirectory == null) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "SILK 编码工作目录不能为空。");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "SILK 编码超时时间必须大于 0。");
        }

        nodeCommand = nodeCommand.trim();
        scriptPath = scriptPath.toAbsolutePath().normalize();
        workDirectory = workDirectory.toAbsolutePath().normalize();
    }
}
