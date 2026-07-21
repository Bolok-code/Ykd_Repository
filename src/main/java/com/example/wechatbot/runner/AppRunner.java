package com.example.wechatbot.runner;

import com.example.wechatbot.service.WechatILinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AppRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AppRunner.class);

    @Autowired
    private WechatILinkService wechatService;

    @Override
    public void run(String... args) throws Exception {
        String qr = wechatService.executeLogin();
        if (qr != null && !qr.isEmpty()) {
            System.out.println(qr);
        }
    }
}