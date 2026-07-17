package com.clitoolbox;

import com.clitoolbox.commands.ChatCommand;
import com.clitoolbox.commands.Command;
import com.clitoolbox.commands.HelpCommand;
import com.clitoolbox.commands.StatusCommand;
import com.clitoolbox.commands.VersionCommand;
import com.clitoolbox.commands.WeatherCommand;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.exception.ExceptionHandler;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Spring Boot 启动完成后，根据第一个参数分发 CLI 命令。
 */
@Component
public class CliApplicationRunner implements ApplicationRunner {
    private final ObjectProvider<HelpCommand> helpCommand;
    private final ObjectProvider<VersionCommand> versionCommand;
    private final ObjectProvider<StatusCommand> statusCommand;
    private final ObjectProvider<WeatherCommand> weatherCommand;
    private final ObjectProvider<ChatCommand> chatCommand;

    public CliApplicationRunner(
            ObjectProvider<HelpCommand> helpCommand,
            ObjectProvider<VersionCommand> versionCommand,
            ObjectProvider<StatusCommand> statusCommand,
            ObjectProvider<WeatherCommand> weatherCommand,
            ObjectProvider<ChatCommand> chatCommand) {
        this.helpCommand = helpCommand;
        this.versionCommand = versionCommand;
        this.statusCommand = statusCommand;
        this.weatherCommand = weatherCommand;
        this.chatCommand = chatCommand;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String[] args = arguments.getSourceArgs();
        String commandName = args.length == 0
                ? "help"
                : args[0].toLowerCase(Locale.ROOT);
        try {
            Command command = switch (commandName) {
                case "help" -> helpCommand.getObject();
                case "version" -> versionCommand.getObject();
                case "status" -> statusCommand.getObject();
                case "weather" -> weatherCommand.getObject();
                case "chat" -> chatCommand.getObject();
                default -> throw new CliException(
                        ErrorCode.INVALID_INPUT,
                        "未知命令: " + commandName + "\n  使用 \"help\" 查看可用命令。");
            };
            command.run(args);
        } catch (Exception e) {
            ExceptionHandler.handle(commandName, e);
        }
    }
}
