package ykd.ykd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.GlobalExceptionHandler;
import ykd.ykd.exception.SystemException;
import ykd.ykd.weather.WeatherService;
import ykd.ykd.wxbox.QrCodeUtil;
import ykd.ykd.wxbox.WxboxService;

import java.util.Scanner;

/**
 * 控制台交互运行器 —— 启动后进入命令行循环，等待用户输入。
 *
 * <p>直接输入城市名查天气，支持的命令：
 * <ul>
 *   <li>help      — 显示帮助信息</li>
 *   <li>version   — 显示当前版本号</li>
 *   <li>status    — 显示程序运行状态</li>
 *   <li>wechat    — 扫码连接微信，双向聊天</li>
 *   <li>1~4       — 异常处理演示</li>
 *   <li>0 / exit  — 退出程序</li>
 * </ul>
 */
@Component
public class ConsoleRunner {

    private static final String VERSION = "1.0.0";
    private static final long START_TIME = System.currentTimeMillis();
    private static final Logger log = LoggerFactory.getLogger(ConsoleRunner.class);

    private final WeatherService weatherService;
    private final WxboxService wxboxService;

    public ConsoleRunner(WeatherService weatherService, WxboxService wxboxService) {
        this.weatherService = weatherService;
        this.wxboxService = wxboxService;
    }

    public void run() {
        printBanner();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n请输入 > ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit") || input.equals("0")) {
                System.out.println("程序已退出，再见！");
                wxboxService.shutdown();
                break;
            }

            GlobalExceptionHandler.run(() -> dispatch(input, scanner));
        }
        scanner.close();
    }

    // ==================== 路由 ====================

    private void dispatch(String input, Scanner scanner) {
        switch (input.toLowerCase()) {
            case "", "help"    -> printHelp();
            case "version"     -> printVersion();
            case "status"      -> printStatus();
            case "wechat"      -> startWechat(scanner);
            case "1", "normal" -> System.out.println("HelloWorld1234567");
            case "2", "business" -> testBusinessException();
            case "3", "system"   -> testSystemException();
            case "4", "unknown"  -> testUnknownException();
            default -> {
                System.out.println(weatherService.queryWeather(input).toDisplayString());
            }
        }
    }

    // ==================== 微信模式 ====================

    private void startWechat(Scanner scanner) {
        System.out.println("正在初始化微信连接...");
        wxboxService.startLogin();

        // 等待并展示二维码
        String qrUrl = wxboxService.getQrUrl();
        if (qrUrl == null) {
            System.out.println("[错误] 获取二维码超时，请重试");
            return;
        }

        // 生成二维码图片，输出可点击链接
        String imagePath = QrCodeUtil.generate(qrUrl);
        if (imagePath != null) {
            QrCodeUtil.printClickableLink(imagePath);
        } else {
            System.out.println("[错误] 二维码生成失败，请手动访问: " + qrUrl);
        }
        System.out.println("请用微信扫描二维码");

        // 轮询等待扫码与登录（显示状态变化）
        long deadline = System.currentTimeMillis() + 120_000; // 2分钟超时
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            if (wxboxService.isConnected()) {
                break; // 已连接
            }
            String status = wxboxService.getScannedStatus();
            if (status != null && !status.equals(lastStatus)) {
                System.out.println("\n" + status);
                lastStatus = status;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!wxboxService.isConnected()) {
            System.out.println("\n登录超时或失败，请重试");
            return;
        }

        System.out.println("\n微信已连接！BotID=" + wxboxService.getBotId());
        System.out.println("自动回复模式已启动");
        System.out.println("对方发城市名 → 自动回复天气");
        System.out.println("输入 exit 退出微信模式\n");

        // 自动回复模式：等待退出即可，收到消息会自动回复
        while (true) {
            String text = scanner.nextLine().trim();

            if (text.equalsIgnoreCase("exit")) {
                System.out.println("已退出微信模式");
                wxboxService.shutdown();
                break;
            }
        }
    }

    // ==================== 命令输出 ====================

    private void printBanner() {
        System.out.println("========================================");
        System.out.println("  欢迎使用 天气查询  v" + VERSION);
        System.out.println("========================================");
        System.out.println("  输入城市名查询天气，例如：北京");
        System.out.println("  输入 help 查看更多命令");
        System.out.println();
    }

    private void printHelp() {
        System.out.println("=========== 帮助信息 ===========");
        System.out.println("  直接输入城市名 — 查询实时天气（如：北京）");
        System.out.println("  help    — 显示帮助信息");
        System.out.println("  version — 显示当前版本号");
        System.out.println("  status  — 显示程序运行状态");
        System.out.println("  wechat  — 扫码连接微信，双向聊天");
        System.out.println("  1~4     — 异常处理演示");
        System.out.println("  0       — 退出程序");
        System.out.println("================================");
    }

    private void printVersion() {
        System.out.println("天气查询 v" + VERSION);
    }

    private void printStatus() {
        long uptime = (System.currentTimeMillis() - START_TIME) / 1000;
        long h = uptime / 3600, m = (uptime % 3600) / 60, s = uptime % 60;
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);

        System.out.println("=========== 运行状态 ===========");
        System.out.printf("  版本    : %s%n", VERSION);
        System.out.printf("  运行时长: %d时%d分%d秒%n", h, m, s);
        System.out.printf("  内存使用: %dMB / %dMB%n", used, max);
        System.out.println("================================");
    }

    // ==================== 测试场景 ====================

    private void testBusinessException() {
        throw BusinessException.invalidParam("cityName", "城市名不能为空");
    }

    private void testSystemException() {
        try {
            throw new java.net.ConnectException("Connection refused");
        } catch (Exception e) {
            throw SystemException.httpError("天气API连接失败", e);
        }
    }

    private void testUnknownException() {
        String[] arr = new String[0];
        System.out.println(arr[10]);
    }
}
