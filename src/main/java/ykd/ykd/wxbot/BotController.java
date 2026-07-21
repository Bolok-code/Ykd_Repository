package ykd.ykd.wxbot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ykd.ykd.config.RuntimeConfig;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class BotController {

    private final RuntimeConfig runtimeConfig;
    private final WeixinBotService weixinBotService;

    public BotController(RuntimeConfig runtimeConfig, WeixinBotService weixinBotService) {
        this.runtimeConfig = runtimeConfig;
        this.weixinBotService = weixinBotService;
    }

    @PostConstruct
    private void autoConnect() {
        if (!weixinBotService.hasSavedSession()) {
            return;
        }
        try {
            String qrUrl = weixinBotService.login();
            if (qrUrl == null) {
                log.info("自动恢复 Session 成功");
            } else {
                log.info("需要扫码登录");
            }
        } catch (Exception e) {
            log.debug("自动连接跳过: {}", e.getMessage());
        }
    }

    @PostMapping("/config/keys")
    public Map<String, Object> saveKeys(@RequestBody Map<String, String> keys) {
        String deepseekKey = keys.get("deepseekApiKey");
        String agnesKey = keys.get("agnesApiKey");
        String elevenlabsKey = keys.get("elevenlabsApiKey");

        if (deepseekKey == null || deepseekKey.isBlank()) {
            return Map.of("success", false, "message", "DeepSeek API Key 不能为空");
        }
        if (agnesKey == null || agnesKey.isBlank()) {
            return Map.of("success", false, "message", "Agnes API Key 不能为空");
        }
        if (elevenlabsKey == null || elevenlabsKey.isBlank()) {
            return Map.of("success", false, "message", "ElevenLabs API Key 不能为空");
        }

        runtimeConfig.setDeepseekApiKey(deepseekKey.trim());
        runtimeConfig.setAgnesApiKey(agnesKey.trim());
        runtimeConfig.setElevenlabsApiKey(elevenlabsKey.trim());
        log.info("API 密钥已更新");
        return Map.of("success", true, "message", "密钥保存成功");
    }

    @GetMapping("/bot/login")
    public Map<String, Object> login() {
        if (!runtimeConfig.isKeysConfigured()) {
            return Map.of("success", false, "message", "请先配置 API 密钥");
        }
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
        boolean keysReady = runtimeConfig.isKeysConfigured();
        return Map.of("connected", weixinBotService.hasSavedSession(), "keysConfigured", keysReady);
    }
}
