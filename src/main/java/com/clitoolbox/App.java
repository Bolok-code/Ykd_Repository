package com.clitoolbox;

import com.clitoolbox.commands.*;
import com.clitoolbox.exception.*;
import com.clitoolbox.weather.WeatherService;

public class App {

    public static void main(String[] args) {
        try {
            String command = (args.length == 0) ? "help" : args[0].toLowerCase();

            Command handler = switch (command) {
                case "help" -> new HelpCommand();
                case "version" -> new VersionCommand();
                case "status" -> new StatusCommand();
                case "weather" -> new WeatherCommand();
                case "chat" -> new ChatCommand();
                default -> null;
            };

            if (handler == null) {
                if (command.equals("weather") || command.equals("chat")) {
                    throw new CliException(
                            ErrorCode.NOT_IMPLEMENTED,
                            "命令 \"" + command + "\" 尚未实现，敬请期待。");
                } else {
                    throw new CliException(
                            ErrorCode.INVALID_INPUT,
                            "未知命令: " + command + "\n  使用 \"help\" 查看可用命令。");
                }
            }

            handler.run(args);
        } catch (Exception e) {
            ExceptionHandler.handle(getCommandName(args), e);
        }
    }

    private static String getCommandName(String[] args) {
        return (args.length == 0) ? "help" : args[0].toLowerCase();
    }
}
