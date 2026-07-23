package ykd.ykd.wxbot;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class BotController {

    private final WeixinBotService weixinBotService;

    public BotController(WeixinBotService weixinBotService) {
        this.weixinBotService = weixinBotService;
    }

    @GetMapping("/bot/login")
    public Map<String, Object> login() {
        try {
            String qrUrl = weixinBotService.login();
            if (qrUrl != null) {
                return Map.of("success", true, "qrUrl", qrUrl, "message", "请扫码登录");
            } else {
                return Map.of("success", true, "message", "Session 已恢复，无需扫码");
            }
        } catch (Exception e) {
            log.error("登录失败", e);
            return Map.of("success", false, "message", "登录失败: " + e.getMessage());
        }
    }

    @PostMapping("/bot/disconnect")
    public Map<String, Object> disconnect() {
        try {
            weixinBotService.stop();
            weixinBotService.deleteSession();
            return Map.of("success", true, "message", "已断开连接");
        } catch (Exception e) {
            log.error("断开连接失败", e);
            return Map.of("success", false, "message", "断开失败: " + e.getMessage());
        }
    }

    @GetMapping("/bot/status")
    public Map<String, Object> status() {
        return Map.of("connected", weixinBotService.hasSavedSession());
    }
}