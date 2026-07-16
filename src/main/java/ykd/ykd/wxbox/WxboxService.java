package ykd.ykd.wxbox;

import com.openilink.ILinkClient;
import com.openilink.auth.LoginCallbacks;
import com.openilink.model.response.LoginResult;
import com.openilink.monitor.MonitorOptions;
import com.openilink.util.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.weather.WeatherResponse;
import ykd.ykd.weather.WeatherService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信连接服务 —— 封装 ILinkClient 的生命周期管理。
 *
 * <p>流程：扫码登录 → 启动消息监听 → 控制台 ↔ 微信双向通信</p>
 */
@Service
public class WxboxService {

    private static final Logger log = LoggerFactory.getLogger(WxboxService.class);

    private final WeatherService weatherService;

    private volatile ILinkClient client;
    private volatile LoginResult loginResult;
    private volatile String lastFromUserId;

    private final AtomicBoolean stopMonitor = new AtomicBoolean(false);
    private Thread loginThread;
    private Thread monitorThread;

    private final AtomicReference<String> qrUrl = new AtomicReference<>();
    private final AtomicReference<String> scannedStatus = new AtomicReference<>(null);
    private final CountDownLatch qrLatch = new CountDownLatch(1);
    private final CountDownLatch connectedLatch = new CountDownLatch(1);

    public WxboxService(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    // ==================== 登录 ====================

    /**
     * 异步启动微信扫码登录流程。
     *
     * <p>调用后立即返回。通过 {@link #getQrUrl()} 阻塞等待二维码 URL，
     * 通过 {@link #waitForConnection(int)} 等待登录完成。</p>
     */
    public void startLogin() {
        loginThread = new Thread(() -> {
            try {
                client = ILinkClient.builder().token("").build();

                loginResult = client.loginWithQR(new LoginCallbacks() {
                    @Override
                    public void onQRCode(String url) {
                        qrUrl.set(url);
                        log.info("获取到登录二维码");
                        qrLatch.countDown();
                    }

                    @Override
                    public void onScanned() {
                        scannedStatus.set("已扫码，请在微信上确认登录...");
                        log.info("用户已扫码");
                    }

                    @Override
                    public void onExpired(int attempt, int max) {
                        log.warn("二维码过期，正在刷新 ({}/{})", attempt, max);
                        scannedStatus.set("二维码过期，正在刷新...(" + attempt + "/" + max + ")");
                    }
                });

                if (loginResult.isConnected()) {
                    log.info("微信登录成功：BotID={}, UserId={}",
                            loginResult.getBotId(), loginResult.getUserId());
                    startMonitor();
                    connectedLatch.countDown();
                } else {
                    log.error("微信登录失败");
                    // 释放 waiter，让调用方感知失败
                    qrLatch.countDown();
                    connectedLatch.countDown();
                }

            } catch (Exception e) {
                log.error("微信登录异常", e);
                connectedLatch.countDown();
            }
        }, "wx-login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    /**
     * 阻塞等待二维码 URL（最多 60 秒）。
     *
     * @return QR 码 URL，超时或异常返回 null
     */
    public String getQrUrl() {
        try {
            if (qrLatch.await(60, TimeUnit.SECONDS)) {
                return qrUrl.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * 阻塞等待登录完成。
     *
     * @param timeoutSeconds 超时时间（秒）
     * @return true 登录成功，false 超时或失败
     */
    public boolean waitForConnection(int timeoutSeconds) {
        try {
            return connectedLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                    && isConnected();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 获取扫码状态文字（onScanned / onExpired 回调后更新）。
     */
    public String getScannedStatus() {
        return scannedStatus.get();
    }

    // ==================== 消息监听 ====================

    private void startMonitor() {
        stopMonitor.set(false);

        MonitorOptions options = MonitorOptions.builder()
                .onError(err -> log.error("Monitor 异常: {}", err.getMessage()))
                .onSessionExpired(() -> {
                    log.warn("微信会话过期");
                    System.out.print("\r[微信] 会话已过期，请重新运行 wechat 登录\n发送 > ");
                })
                .build();

        monitorThread = new Thread(() -> {
            client.monitor(msg -> {
                String text = MessageHelper.extractText(msg);
                if (text != null && !text.isEmpty()) {
                    lastFromUserId = msg.getFromUserId();
                    log.info("收到微信消息：from={}, text={}", lastFromUserId, text);
                    System.out.print("\r[微信] " + text);

                    // 自动查询天气并回复
                    String reply;
                    try {
                        WeatherResponse wr = weatherService.queryWeather(text);
                        reply = wr.toDisplayString();
                        log.info("自动回复天气：to={}, city={}", lastFromUserId, text);
                    } catch (BusinessException be) {
                        reply = "抱歉，" + toUserFriendly(be.getMessage());
                        log.warn("自动回复业务错误：{}", be.getMessage());
                    } catch (Exception e) {
                        reply = "查询失败，请稍后重试";
                        log.error("自动回复系统错误", e);
                    }

                    try {
                        client.push(lastFromUserId, reply);
                        System.out.print(" → 已回复\n> ");
                    } catch (Exception e) {
                        log.error("发送回复失败", e);
                        System.out.print(" → 发送失败\n> ");
                    }
                }
            }, options, stopMonitor);
        }, "wx-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    // ==================== 发送消息 ====================

    /**
     * 向微信用户发送文本消息（使用 monitor 缓存的 contextToken）。
     */
    public void sendMessage(String text) {
        if (client == null || !isConnected()) {
            System.out.println("[微信] 未连接，无法发送");
            return;
        }
        if (lastFromUserId == null) {
            System.out.println("[微信] 对方尚未发送消息，请先在微信上说句话");
            return;
        }
        try {
            client.push(lastFromUserId, text);
            log.info("发送微信消息：to={}, text={}", lastFromUserId, text);
        } catch (Exception e) {
            log.error("发送微信消息失败", e);
            System.out.println("[微信] 发送失败: " + e.getMessage());
        }
    }

    // ==================== 状态 ====================

    public boolean isConnected() {
        return loginResult != null && loginResult.isConnected();
    }

    public String getBotId() {
        return loginResult != null ? loginResult.getBotId() : null;
    }

    /**
     * 从异常消息中提取用户友好的部分。
     * 例如 "参数校验失败 [city]: 城市名不能为空" → "城市名不能为空"
     */
    private static String toUserFriendly(String errorMsg) {
        if (errorMsg == null) return "未知错误";
        int idx = errorMsg.lastIndexOf(": ");
        return idx >= 0 ? errorMsg.substring(idx + 2).trim() : errorMsg;
    }

    public void shutdown() {
        stopMonitor.set(true);
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
        if (loginThread != null) {
            loginThread.interrupt();
        }
    }
}
