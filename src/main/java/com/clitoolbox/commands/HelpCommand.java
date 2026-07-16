package com.clitoolbox.commands;

import com.clitoolbox.config.AppConfig;

/**
 * help 命令 —— 显示程序帮助信息
 */
public class HelpCommand implements Command {

    @Override
    public void run(String[] args) {
        System.out.println(AppConfig.APP_NAME + " — " + AppConfig.APP_DESCRIPTION);
        System.out.println();
        System.out.println("用法: java -jar cli-toolbox.jar <command> [options]");
        System.out.println();
        System.out.println("可用命令:");
        for (String cmd : AppConfig.COMMANDS) {
            System.out.println("  " + cmd);
        }
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar cli-toolbox.jar version");
        System.out.println("  java -jar cli-toolbox.jar status");
        System.out.println("  java -jar cli-toolbox.jar weather --city 北京");
    }
}
