package ykd.ykd.wxbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class BotController {

    private final WeixinBotService weixinBotService;

    public BotController(WeixinBotService weixinBotService) {
        this.weixinBotService = weixinBotService;
    }

    @PostMapping("/bot/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, String> body) {
        String botUserId = (body != null) ? body.get("botUserId") : null;
        if (botUserId == null || botUserId.isBlank()) {
            botUserId = "bot-" + System.currentTimeMillis();
        }
        try {
            if (weixinBotService.isOnline(botUserId)) {
                return Map.of("success", false, "message", "该账号已在线: " + botUserId);
            }
            String qrUrl = weixinBotService.login(botUserId);
            if (qrUrl != null) {
                return Map.of("success", true, "botUserId", botUserId, "qrUrl", qrUrl, "message", "请扫码登录");
            } else {
                return Map.of("success", true, "botUserId", botUserId, "message", "Session 已恢复，无需扫码");
            }
        } catch (Exception e) {
            log.error("登录失败", e);
            return Map.of("success", false, "message", "登录失败: " + e.getMessage());
        }
    }

    @PostMapping("/bot/disconnect")
    public Map<String, Object> disconnect(@RequestParam String botUserId) {
        try {
            weixinBotService.disconnect(botUserId);
            return Map.of("success", true, "message", "已断开连接: " + botUserId);
        } catch (Exception e) {
            log.error("断开连接失败", e);
            return Map.of("success", false, "message", "断开失败: " + e.getMessage());
        }
    }

    @GetMapping("/bot/status")
    public Map<String, Object> status() {
        return Map.of(
                "online", weixinBotService.hasAnyOnline(),
                "botUsers", weixinBotService.getActiveBotUsers(),
                "pendingUsers", weixinBotService.getPendingBotUsers()
        );
    }
}
