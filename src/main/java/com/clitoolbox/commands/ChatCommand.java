package com.clitoolbox.commands;

import com.clitoolbox.ilink.ILinkService;

public class ChatCommand implements Command {
    @Override
    public void run(String[] args) {
        ILinkService service = new ILinkService();
        if (args.length > 1 && args[1].equals("login")) {
            service.login();
        } else {
            service.listen();
        }
    }
}
