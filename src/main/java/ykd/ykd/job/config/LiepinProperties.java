package ykd.ykd.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "liepin")
public class LiepinProperties {
    private boolean enabled;
    private boolean headless;
    private String baseUrl = "https://www.liepin.com";
    private String profileDirectory = "./work/liepin/browser-profile";
    private String browserExecutablePath;
    private int navigationTimeoutSeconds = 45;
    private int maxSearchResults = 10;
    private int maxSearchPages = 3;
    private int maxApplicationsPerTask = 1;
    private boolean requireConfirmation = true;
    private String defaultSalaryCode = "";
    private String defaultGreeting = "请先在猎聘 App 中设置默认招呼语；程序确认岗位后只点击“聊一聊”，不会额外重复发送文本。";
}