package ykd.ykd.wxbot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.processor.MessageProcessor;
import ykd.ykd.processor.ProcessResult;

import java.util.List;

/**
 * 微信 iLink 智能机器人服务。
 *
 * <p>启动后通过二维码扫码登录微信，监听用户消息，将消息交由 {@link MessageProcessor} 处理，
 * 并将处理结果（文本/图片）发送给用户。</p>
 */
@Slf4j
@Service
public class WeixinBotService {

    private final MessageProcessor messageProcessor;
    private final OnLoginListener onLoginListener;

    private ILinkClient client;
    private volatile boolean running = true;

    public WeixinBotService(MessageProcessor messageProcessor) {
        this.messageProcessor = messageProcessor;
        this.onLoginListener = new OnLoginListener() {
            @Override
            public void onLoginSuccess(LoginContext context) {
                log.info("微信登录成功，botId = {}", context.getBotId());
            }

            @Override
            public void onLoginFailure(Throwable throwable) {
                log.error("微信登录失败: {}", throwable.getMessage());
            }
        };
    }

    /**
     * 启动微信机器人。
     *
     * <p>Spring Bean 初始化完成后由 {@link PostConstruct} 自动触发，
     * 通过守护线程异步执行 {@link #runBot()}，避免阻塞容器启动。</p>
     */
    @PostConstruct
    public void start() {
        Thread botThread = new Thread(this::runBot, "wx-bot");
        botThread.setDaemon(true);
        botThread.start();
    }

    /**
     * 优雅关闭微信机器人服务。
     *
     * <p>Spring 容器销毁 Bean 时自动调用：置位 running 标志通知轮询循环退出，
     * 随后关闭 iLink 客户端释放 HTTP 连接池等资源。</p>
     */
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

    /**
     * 微信机器人主循环，由守护线程 {@code wx-bot} 执行。
     */
    private void runBot() {
        try {
            client = ILinkClient.builder()
                    .onLogin(onLoginListener)
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
                    // 检查是否有后台完成的视频需要发送
                    sendCompletedVideo();
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

    /**
     * 消息处理入口：委托 {@link MessageProcessor} 处理后发送结果。
     */
    private void handleMessage(WeixinMessage msg) {
        ProcessResult result = messageProcessor.process(msg, client);
        if (result == null) {
            return;
        }

        switch (result.type()) {
            case IMAGE -> safeSendImage(result.userId(), result.data());
            case VIDEO -> safeSendVideo(result.userId(), result.data());
            case VOICE -> safeSendVoice(result.userId(), result.data());
            case TEXT -> safeSendText(result.userId(), result.text());
        }
    }

    /**
     * 安全发送文本消息。
     *
     * <p>异常仅记录日志不向上抛出，确保发送失败不会中断主流程。</p>
     */
    private void safeSendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (Exception e) {
            log.error("发送消息失败: userId={}, text={}", userId, text, e);
        }
    }

    /**
     * 安全发送图片消息。
     *
     * <p>异常仅记录日志不向上抛出，确保发送失败不会中断主流程。</p>
     */
    private void safeSendImage(String userId, byte[] imageData) {
        try {
            client.sendImage(userId, imageData, "image.png", "");
            log.info("[Bot] 图片发送成功: userId={}, size={}KB", userId, imageData.length / 1024);
        } catch (Exception e) {
            log.error("发送图片失败: userId={}", userId, e);
        }
    }

    /**
     * 安全发送语音消息。
     *
     * <p>异常仅记录日志不向上抛出，确保发送失败不会中断主流程。</p>
     */
    private void safeSendVoice(String userId, byte[] voiceData) {
        try {
            client.sendFile(userId, voiceData, "voice.mp3", "语音消息");
            log.info("[Bot] 语音文件发送成功: userId={}, size={}KB", userId, voiceData.length / 1024);
        } catch (Exception e) {
            log.error("发送语音失败: userId={}", userId, e);
        }
    }

    /**
     * 安全发送视频消息。
     *
     * <p>异常仅记录日志不向上抛出，确保发送失败不会中断主流程。</p>
     */
    private void safeSendVideo(String userId, byte[] videoData) {
        try {
            client.sendVideo(userId, videoData, "video.mp4", 0, "视频");
            log.info("[Bot] 视频发送成功: userId={}, size={}KB", userId, videoData.length / 1024);
        } catch (Exception e) {
            log.error("发送视频失败: userId={}", userId, e);
        }
    }

    /**
     * 检查并发送后台完成的视频（来自 {@link ykd.ykd.processor.VideoTaskManager} 异步轮询）。
     */
    private void sendCompletedVideo() {
        ProcessResult result = messageProcessor.pollCompletedVideo();
        while (result != null) {
            log.info("[Bot] 推送后台完成的视频: userId={}", result.userId());
            safeSendVideo(result.userId(), result.data());
            result = messageProcessor.pollCompletedVideo();
        }
    }


}
