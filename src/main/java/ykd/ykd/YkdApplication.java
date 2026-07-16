package ykd.ykd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Ykd 天气查询 —— Spring Boot 启动类。
 *
 * <p>启动后：
 * <ul>
 *   <li>内嵌 Tomcat 在 8080 端口监听（为后续 Web/微信功能预留）</li>
 *   <li>控制台交互循环启动，直接输入城市名查天气</li>
 * </ul>
 */
@SpringBootApplication
public class YkdApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx =
                SpringApplication.run(YkdApplication.class, args);

        // 启动控制台交互循环
        ConsoleRunner runner = ctx.getBean(ConsoleRunner.class);
        runner.run();

        // 控制台退出后关闭 Spring 上下文
        SpringApplication.exit(ctx, () -> 0);
    }
}
