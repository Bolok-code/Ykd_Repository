package com.clitoolbox.config;

/**
 * 应用全局配置常量
 */
public final class AppConfig {

    private AppConfig() {} // 工具类，禁止实例化

    public static final String APP_NAME = "CLI-Toolbox";
    public static final String APP_VERSION = "0.1.0";
    public static final String APP_DESCRIPTION =
            "多模块命令行工具箱 —— 集成基础命令、天气查询、iLink SDK 等功能";

    public static final String[] COMMANDS = {
        "help    — 显示此帮助信息",
        "version — 显示当前版本号",
        "status  — 显示程序运行状态",
        "weather — 查询实时天气（按城市名）",
        "chat    — 通过 iLink 发送消息并接收回复",
    };
}
