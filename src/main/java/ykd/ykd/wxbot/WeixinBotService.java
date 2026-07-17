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
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.llm.service.LlmService;
import java.util.List;

/**
 * 微信 iLink 智能机器人服务。
 *
 * <p>启动后通过二维码扫码登录微信，监听用户消息，交由 {@link LlmService} 进行
 * AI 对话（含天气查询工具调用），并以文本形式回复用户。</p>
 *
 * <p>生命周期由 Spring 容器管理：{@code @PostConstruct} 启动守护线程，
 * {@code @PreDestroy} 优雅释放 SDK 资源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeixinBotService {

    private final LlmService llmService;
    private ILinkClient client;
    private volatile boolean running = true;

    /**
     * 启动微信机器人。
     *
     * <p>Spring Bean 初始化完成后由 {@link PostConstruct} 自动触发。</p>
     * <p>通过守护线程异步执行 {@link #runBot()}，避免阻塞 Spring 容器启动。</p>
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
     * <p>Spring 容器销毁 Bean 时自动调用，执行顺序：</p>
     * <ol>
     *   <li>置位 {@code running = false}，通知消息轮询循环退出</li>
     *   <li>若客户端已初始化，调用 {@code close()} 释放 HTTP 连接池、线程池等资源</li>
     * </ol>
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
     *
     * <p>执行流程分为两个阶段：</p>
     * <ol>
     *   <li>扫码登录：构建 {@link ILinkClient}，获取二维码并阻塞等待用户扫码确认</li>
     *   <li>消息轮询：循环调用 {@code getUpdates()} 拉取消息，分发至 {@link #handleMessage(WeixinMessage)} 处理</li>
     * </ol>
     *
     * <p>最外层 {@code try-catch} 确保任何未预期异常都不会导致线程静默终止。</p>
     */
    private void runBot() {
        try {
            /*
             * ========== 阶段一：扫码登录 ==========
             * 构建客户端 → 获取二维码 → 阻塞等待用户扫码确认
             */
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
            //向微信服务器申请二维码
            String qrCodeContent = client.executeLogin();
            log.info("========================================");
            log.info("请将以下URL转为二维码后，用微信扫码登录：");
            log.info("{}", qrCodeContent);
            log.info("========================================");

            LoginContext context = client.getLoginFuture().get();
            log.info("登录完成，botId = {}，开始监听消息...", context.getBotId());

            /*
             * ========== 阶段二：消息轮询 ==========
             * 长轮询拉取消息，逐条分发处理；异常时退避 3 秒后重试
             */
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


    /**
     * 处理单条微信消息：提取文本后交由 LLM 生成回复并返回。
     *
     * <p>忽略无消息条目或非文本内容的入站消息。</p>
     *
     * @param msg 微信 iLink 消息对象，包含发送者 ID 和消息条目列表
     */
    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();

        if (msg.getItem_list() == null) {
            log.info("收到非文本消息(无item_list): userId={}", fromUserId);
            return;
        }

        String text = extractText(msg);
        if (text == null || text.isBlank()) {
            log.info("收到非文本消息(无文字): userId={}", fromUserId);
            return;
        }

        log.info("收到消息: userId={}, text={}", fromUserId, text);
        try {
            String reply = llmService.chat(text.trim());
            safeSendText(fromUserId, reply);
        } catch (Exception e) {
            log.error("AI调用异常: userId={}, text={}", fromUserId, text, e);
            safeSendText(fromUserId, "❌ " + ErrorCode.AI_CALL_FAILED.getDefaultMessage());
        }
    }

    /**
     * 安全发送文本消息，异常仅记录日志不向上抛出。
     *
     * <p>用于错误提示场景，确保发送失败不会中断主流程。</p>
     *
     * @param userId 微信用户 ID，格式为 {@code xxx@im.wechat}
     * @param text   待发送的文本内容
     */
    private void safeSendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (Exception e) {
            log.error("发送消息失败: userId={}, text={}", userId, text, e);
        }
    }

    /**
     * 从微信消息条目列表中提取第一条文本内容。
     *
     * <p>遍历 {@code item_list}，返回首个非空 {@code text_item} 的文本；
     * 若消息不含文本条目则返回 {@code null}。</p>
     *
     * @param msg 微信 iLink 消息对象
     * @return 提取到的文本字符串，无文本内容时返回 {@code null}
     */
    private String extractText(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

}
