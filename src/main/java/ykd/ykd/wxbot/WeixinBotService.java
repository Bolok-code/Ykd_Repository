package ykd.ykd.wxbot;


import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.weather.api.dto.WeatherResponse;
import ykd.ykd.weather.service.WeatherService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeixinBotService {

    private final WeatherService weatherService;

    private ILinkClient client;
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        Thread botThread = new Thread(this::runBot, "wx-bot");
        botThread.setDaemon(true);
        botThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭iLink客户端异常", e);
            }
        }
    }

    private void runBot() {
        try {
            client = ILinkClient.builder()
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            log.info("微信登录成功，botId = {}", context.getBotId());
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("微信登录失败: {}", throwable.getMessage());
                        }
                    })
                    .build();

            String qrCodeContent = client.executeLogin();
            log.info("========================================");
            log.info("请将以下URL转为二维码后，用微信扫码登录：");
            log.info("{}", qrCodeContent);
            log.info("========================================");

            LoginContext context = client.getLoginFuture().get();
            log.info("登录完成，botId = {}，开始监听消息...", context.getBotId());

            while (running) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    if (messages != null) {
                        for (WeixinMessage msg : messages) {
                            handleMessage(msg);
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.error("消息轮询异常，3秒后重试", e);
                        Thread.sleep(3000);
                    }
                }
            }
        } catch (Exception e) {
            log.error("微信机器人运行异常", e);
        }
    }

    private void handleMessage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return;

        String fromUserId = msg.getFrom_user_id();
        String cityName = extractText(msg);

        if (cityName == null || cityName.isBlank()) return;

        log.info("收到天气查询: userId={}, city={}", fromUserId, cityName);

        try {
            client.startTyping(fromUserId);

            WeatherResponse weather = weatherService.getWeatherByCity(cityName.trim(), "base");
            String reply = formatWeather(weather);
            client.sendText(fromUserId, reply);
            log.info("已回复天气: userId={}, city={}", fromUserId, cityName);
        } catch (BusinessException e) {
            safeSendText(fromUserId, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("查询天气异常", e);
            safeSendText(fromUserId, "❌ 查询天气失败，请稍后重试");
        } finally {
            safeStopTyping(fromUserId);
        }
    }

    private void safeStopTyping(String userId) {
        try {
            client.stopTyping(userId);
        } catch (Exception e) {
            log.error("停止输入态失败: userId={}", userId, e);
        }
    }

    private void safeSendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (Exception e) {
            log.error("发送消息失败: userId={}, text={}", userId, text, e);
        }
    }

    private String extractText(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private String formatWeather(WeatherResponse w) {
        return String.format("""
                        🌤️ %s %s 天气

                        天气：%s
                        温度：%s°C
                        湿度：%s%%
                        风向：%s
                        风力：%s级
                        更新时间：%s""",
                w.province(), w.city(),
                w.weather(), w.temperature(), w.humidity(),
                w.windDirection(), w.windPower(), w.reportTime());
    }
}
