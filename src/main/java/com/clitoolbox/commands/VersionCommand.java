package com.clitoolbox.commands;

import com.clitoolbox.config.AppConfig;
import org.springframework.stereotype.Component;

/**
 * version 命令 —— 显示当前版本号
 */
@Component
public class VersionCommand implements Command {

    @Override
    public void run(String[] args) {
        System.out.println(AppConfig.APP_NAME + " v" + AppConfig.APP_VERSION);
    }
}
