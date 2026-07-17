package com.clitoolbox.commands;

import com.clitoolbox.ilink.ILinkService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
public class ChatCommand implements Command {
    private final ILinkService service;

    public ChatCommand(ILinkService service) {
        this.service = service;
    }

    @Override
    public void run(String[] args) {
        if (args.length > 1 && args[1].equals("login")) {
            service.login();
        } else {
            service.listen();
        }
    }
}
