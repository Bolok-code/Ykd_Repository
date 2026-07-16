package com.clitoolbox.ilink;

import com.openilink.ILinkClient;
import com.openilink.auth.LoginCallbacks;
import com.openilink.model.response.LoginResult;
import com.openilink.monitor.MonitorOptions;
import com.openilink.exception.NoContextTokenException;
import com.openilink.util.MessageHelper;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.weather.WeatherResult;
import com.clitoolbox.weather.WeatherService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ILinkService {
    private static final Logger LOG = Logger.getLogger(ILinkService.class.getName());
    private static final String TOKEN_FILE = "work/bot-token.txt";
    private static final String BUF_FILE = "work/bot-buf.txt";
    private ILinkClient client;

    public void login() {
        System.out.println("正在获取登录二维码...");
        client = ILinkClient.builder().token("").build();
        try {
            LoginResult result = client.loginWithQR(new LoginCallbacks() {
                @Override public void onQRCode(String url) {
                    System.out.println("========== iLink 登录 ==========");
                    System.out.println("请用微信扫描以下二维码:");
                    System.out.println(url);
                    System.out.println("================================");
                }
                @Override public void onScanned() {
                    System.out.println("已扫码！请在微信上确认登录...");
                }
                @Override public void onExpired(int attempt, int max) {
                    System.out.println("二维码已过期，正在刷新... (" + attempt + "/" + max + ")");
                }
            });
            if (!result.isConnected()) throw new CliException(ErrorCode.NETWORK_ERROR, "登录失败");
            saveToken(result.getBotToken());
            System.out.println("登录成功! BotID=" + result.getBotId() + " (Token 已保存)");
            System.out.println("现在开始监听消息...\n");
            listen();
        } catch (CliException e) { throw e; }
        catch (Exception e) { throw new CliException(ErrorCode.UNKNOWN, "登录失败: " + e.getMessage()); }
    }

    public void listen() {
        String token = loadToken();
        if (token == null || token.isEmpty()) { login(); return; }
        client = ILinkClient.builder().token(token).build();
        System.out.println("正在连接 iLink 消息服务...");
        AtomicBoolean stop = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { stop.set(true); System.out.println("\n断开连接..."); }));
        MonitorOptions options = MonitorOptions.builder()
            .initialBuf(loadBuf())
            .onBufUpdate(buf -> saveBuf(buf))
            .onError(err -> LOG.warning("监听错误: " + err.getMessage()))
            .onSessionExpired(() -> { System.out.println("会话已过期，请重新登录。"); deleteTokenFile(); })
            .build();
        System.out.println("已连接！发送城市名即可查询天气 (按 Ctrl+C 停止)...\n");
        client.monitor(msg -> {
            String userId = msg.getFromUserId();
            String text = MessageHelper.extractText(msg);
            if (text != null && !text.isEmpty()) {
                System.out.println("[" + userId + "] " + text);
                try {
                    client.push(userId, processMessage(text));
                    System.out.println("  -> 已回复");
                } catch (NoContextTokenException e) { System.out.println("  -> 无法回复: 缺少会话上下文"); }
                catch (Exception e) { System.out.println("  -> 回复失败: " + e.getMessage()); }
            }
        }, options, stop);
    }

    /** 智能消息处理: XX天气 → 查天气 | 纯城市名 → 查天气 | 其他 → 原样回复 */
    private String processMessage(String text) {
        String city = extractCity(text);
        if (city != null) return tryWeather(city, text);
        if (text.length() >= 2 && text.length() <= 6) {
            String r = tryWeather(text, null);
            if (r != null) return r;
        }
        return "已收到您的消息: " + text;
    }

    private String tryWeather(String city, String fallback) {
        try {
            WeatherResult r = new WeatherService().query(city);
            return formatWeather(r);
        } catch (Exception e) { return fallback; }
    }

    private String extractCity(String text) {
        for (String p : new String[]{"(.+?)天气.*","(.+?)气温.*","(.+?)多少度.*","(.+?)温度.*"}) {
            Matcher m = Pattern.compile(p).matcher(text);
            if (m.matches()) return m.group(1).trim();
        }
        return null;
    }

    private String formatWeather(WeatherResult r) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return "【" + r.city() + "实时天气】\n"
            + r.weatherDescription() + "，" + String.format("%.0f", r.temperature()) + "C"
            + " (体感 " + String.format("%.0f", r.feelsLike()) + "C)\n"
            + "今日 " + String.format("%.0f", r.lowTemp()) + "~" + String.format("%.0f", r.highTemp()) + "C\n" + "湿度 " + r.humidity() + "% | 风速 " + r.windSpeed() + " m/s\n"
            + "更新时间: " + sdf.format(new Date(r.updateTime()));
    }

    private void saveToken(String s) { try { Files.createDirectories(Paths.get("work")); Files.writeString(Paths.get(TOKEN_FILE), s, StandardCharsets.UTF_8); } catch (IOException e) { LOG.warning("无法保存 Token"); } }
    private String loadToken() { try { Path p = Paths.get(TOKEN_FILE); if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8).trim(); } catch (IOException e) {} return null; }
    private void saveBuf(String s) { try { Files.writeString(Paths.get(BUF_FILE), s, StandardCharsets.UTF_8); } catch (IOException e) {} }
    private String loadBuf() { try { Path p = Paths.get(BUF_FILE); if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8).trim(); } catch (IOException e) {} return null; }
    private void deleteTokenFile() { try { Files.deleteIfExists(Paths.get(TOKEN_FILE)); Files.deleteIfExists(Paths.get(BUF_FILE)); } catch (IOException e) {} }
}