package com.example.wechatbot.service;
import com.example.wechatbot.handler.MessageHandler;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class WechatILinkService {
    private static final Logger log = LoggerFactory.getLogger(WechatILinkService.class);
    @Autowired
    private MessageHandler messageHandler;
    private ILinkClient client;
    private volatile boolean loggedIn = false;
    private LoginContext loginContext;

    public void createClient() {
        log.info("正在创建iLink客户端...");
        ILinkConfig hbConfig = ILinkConfig.builder().heartbeatIntervalMs(3000L).build();
        client = ILinkClient.builder().config(hbConfig)
            .onLogin(new OnLoginListener() {
                public void onLoginSuccess(LoginContext context) {
                    loginContext = context; loggedIn = true;
                    log.info("登录成功! botId={}, userId={}", context.getBotId(), context.getUserId());
                    System.out.println("\n✅ 微信登录成功！\n   botId: " + context.getBotId() + "\n   userId: " + context.getUserId());
                    startMessagePolling();
                }
                public void onLoginFailure(Throwable throwable) {
                    loggedIn = false;
                    log.error("登录失败: {}", throwable.getMessage());
                    System.out.println("\n❌ 登录失败: " + throwable.getMessage());
                }
            })
            .onMessage(new OnMessageListener() {
                public void onMessages(List<WeixinMessage> messages) {
                    for (WeixinMessage msg : messages) { handleIncomingMessage(msg); }
                }
            }).build();
        log.info("iLink客户端创建成功");
    }

    public String executeLogin() {
        if (client == null) createClient();
        try {
            log.info("正在获取登录二维码...");
            String qrCodeContent = client.executeLogin();
            log.info("获取二维码成功");
            return qrCodeContent;
        } catch (Exception e) { log.error("获取二维码失败", e); return null; }
    }

    private void handleIncomingMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        if (msg.getItem_list() != null) {
            for (MessageItem item : msg.getItem_list()) {
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    log.info("收到消息: from={}, text={}", fromUserId, text);
                    String reply = messageHandler.processMessage(text, fromUserId);
                    if (reply != null) {
                        if (reply.startsWith("VOICE:")) {
                            String[] parts = reply.substring(6).split(",", 3);
                            if (parts.length == 3) {
                                byte[] ad = java.util.Base64.getDecoder().decode(parts[2]);
                                try { client.sendFile(fromUserId, ad, "voice.mp3", ""); log.info("语音已发送: to={}", fromUserId); }
                                catch (Exception e) { log.error("发送语音失败", e); }
                            } continue;
                        }
                        if (reply.startsWith("IMG:")) {
                            byte[] d = java.util.Base64.getDecoder().decode(reply.substring(4));
                            sendImage(fromUserId, d);
                        } else { sendText(fromUserId, reply); }
                    }
                }
                if (item.getVoice_item() != null) {
                    try {
                        String transcribed = item.getVoice_item().getText();
                        log.info("语音消息: from={}, text={}", fromUserId, transcribed);
                        if (transcribed != null && !transcribed.isEmpty()) {
                            String reply = messageHandler.processMessage(transcribed, fromUserId);
                            sendText(fromUserId, reply != null ? reply : "无法处理");
                        }
                    } catch (Exception e) { log.error("处理语音失败", e); }
                }
                if (item.getImage_item() != null) {
                    try {
                        byte[] imgData = client.downloadImageFromMessageItem(item);
                        String result = messageHandler.processImage(imgData);
                        sendText(fromUserId, result != null ? result : "无法识别");
                    } catch (Exception e) { log.error("处理图片失败", e); }
                }
            }
        }
    }

    public void sendText(String toUserId, String text) {
        if (client == null || !loggedIn) { log.warn("未登录，无法发送消息"); return; }
        try {
            client.sendText(toUserId, text);
            log.info("消息已发送: to={}, text={}", toUserId, text.length() > 50 ? text.substring(0, 50) + "..." : text);
        } catch (Exception e) { log.error("发送消息失败", e); }
    }

    private void startMessagePolling() { log.info("消息监听已启动 - SDK自动处理"); }
    public boolean isLoggedIn() { return loggedIn; }
    public LoginContext getLoginContext() { return loginContext; }

    public void sendImage(String toUserId, byte[] data) {
        if (client == null || !loggedIn) return;
        try { client.sendImage(toUserId, data, "image.png", ""); }
        catch (Exception e) { log.error("Send image fail", e); }
    }

    @PreDestroy
    public void close() {
        log.info("关闭iLink客户端...");
        if (client != null) client.close();
        loggedIn = false;
        log.info("iLink客户端已关闭");
    }
}
