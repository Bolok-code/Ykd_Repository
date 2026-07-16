package com.clitoolbox.commands;

import com.clitoolbox.config.AppConfig;

/**
 * version 命令 —— 显示当前版本号
 */
public class VersionCommand implements Command {

    @Override
    public void run(String[] args) {
        System.out.println(AppConfig.APP_NAME + " v" + AppConfig.APP_VERSION);
    }
}
