package com.clitoolbox.commands;

import com.clitoolbox.config.AppConfig;
import org.springframework.stereotype.Component;

/**
 * status 命令 —— 显示程序运行状态（JVM、OS、uptime 等）
 */
@Component
public class StatusCommand implements Command {

    private static final long START_TIME = System.currentTimeMillis();

    @Override
    public void run(String[] args) {
        long uptimeMs = System.currentTimeMillis() - START_TIME;
        long uptimeSec = uptimeMs / 1000;
        String uptimeStr = (uptimeSec >= 60)
                ? (uptimeSec / 60) + " 分 " + (uptimeSec % 60) + " 秒"
                : uptimeSec + " 秒";

        String javaVersion = System.getProperty("java.version", "未知");
        String osName = System.getProperty("os.name", "未知");
        String osArch = System.getProperty("os.arch", "未知");

        System.out.println("应用名称:    " + AppConfig.APP_NAME);
        System.out.println("版本号:      v" + AppConfig.APP_VERSION);
        System.out.println("运行时间:    " + uptimeStr);
        System.out.println("Java 版本:   " + javaVersion);
        System.out.println("系统平台:    " + osName);
        System.out.println("架构:        " + osArch);
        System.out.println("状态:        [OK] 正常运行");
    }
}
