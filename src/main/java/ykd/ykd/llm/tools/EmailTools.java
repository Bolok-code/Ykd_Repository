package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.email.model.EmailAccount;
import ykd.ykd.email.repository.EmailAccountRepository;
import ykd.ykd.email.service.EmailService;
import ykd.ykd.processor.UserContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EmailTools {

    private final EmailService emailService;
    private final EmailAccountRepository emailAccountRepository;
    private final UserContext userContext;

    private static final Map<String, String[]> PROVIDER_IMAP = new HashMap<>();

    static {
        PROVIDER_IMAP.put("qq.com", new String[]{"imap.qq.com", "993"});
        PROVIDER_IMAP.put("foxmail.com", new String[]{"imap.qq.com", "993"});
        PROVIDER_IMAP.put("163.com", new String[]{"imap.163.com", "993"});
        PROVIDER_IMAP.put("126.com", new String[]{"imap.126.com", "993"});
        PROVIDER_IMAP.put("yeah.net", new String[]{"imap.yeah.net", "993"});
        PROVIDER_IMAP.put("gmail.com", new String[]{"imap.gmail.com", "993"});
        PROVIDER_IMAP.put("outlook.com", new String[]{"outlook.office365.com", "993"});
        PROVIDER_IMAP.put("hotmail.com", new String[]{"outlook.office365.com", "993"});
        PROVIDER_IMAP.put("yahoo.com", new String[]{"imap.mail.yahoo.com", "993"});
        PROVIDER_IMAP.put("sina.com", new String[]{"imap.sina.com", "993"});
        PROVIDER_IMAP.put("aliyun.com", new String[]{"imap.aliyun.com", "993"});
    }

    public EmailTools(EmailService emailService,
                      EmailAccountRepository emailAccountRepository,
                      UserContext userContext) {
        this.emailService = emailService;
        this.emailAccountRepository = emailAccountRepository;
        this.userContext = userContext;
    }

    @Tool(description = "绑定邮箱账号。用户首次使用邮件功能前必须先绑定邮箱。需要提供邮箱地址和授权码（非登录密码）")
    public String bindEmail(
            @ToolParam(description = "邮箱地址，如 123456@qq.com") String email,
            @ToolParam(description = "邮箱授权码（不是登录密码）。QQ邮箱/163邮箱需要在设置中开启IMAP并生成授权码") String authorizationCode) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) return "❌ 无法识别用户身份";

        log.info("[EmailTools] 绑定邮箱: userId={}, email={}", userId, email);

        try {
            String domain = email.substring(email.indexOf("@") + 1).toLowerCase();
            String[] imapConfig = PROVIDER_IMAP.get(domain);

            String imapHost;
            int imapPort;
            if (imapConfig != null) {
                imapHost = imapConfig[0];
                imapPort = Integer.parseInt(imapConfig[1]);
            } else {
                imapHost = "imap." + domain;
                imapPort = 993;
            }

            EmailAccount account = new EmailAccount(email, authorizationCode, imapHost, imapPort, true);

            emailAccountRepository.save(userId, account);

            log.info("[EmailTools] 邮箱绑定成功: userId={}, email={}, imap={}:{}", userId, email, imapHost, imapPort);
            return "✅ 邮箱 " + maskEmail(email) + " 绑定成功！现在可以查看和搜索邮件了。";

        } catch (Exception e) {
            log.error("[EmailTools] 绑定邮箱失败: {}", e.getMessage(), e);
            return "❌ 绑定失败：" + e.getMessage();
        }
    }

    @Tool(description = "查看最新邮件。获取收件箱中最近的邮件列表，包括发件人、主题和时间")
    public String readLatestEmails(
            @ToolParam(description = "要查看的邮件数量，默认5") int count) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) return "❌ 无法识别用户身份";

        EmailAccount account = emailAccountRepository.findByUserId(userId);
        if (account == null) {
            return "⚠️ 你还没有绑定邮箱，请先调用 bindEmail 绑定你的邮箱账号和授权码。";
        }

        log.info("[EmailTools] 查看最新邮件: userId={}, count={}", userId, count);

        try {
            int actualCount = count <= 0 ? 5 : Math.min(count, 20);
            List<EmailService.EmailMessage> messages = emailService.fetchLatest(account, actualCount);

            if (messages.isEmpty()) {
                return "📭 收件箱中没有邮件";
            }

            StringBuilder sb = new StringBuilder("📬 最新 ").append(messages.size()).append(" 封邮件：\n\n");
            for (int i = 0; i < messages.size(); i++) {
                EmailService.EmailMessage msg = messages.get(i);
                sb.append(i + 1).append(". ");
                sb.append("【").append(msg.subject()).append("】\n");
                sb.append("   发件人：").append(msg.from()).append("\n");
                sb.append("   时间：").append(msg.date()).append("\n");
                if (msg.body() != null && !msg.body().isBlank()) {
                    String preview = msg.body().length() > 200
                            ? msg.body().substring(0, 200) + "..."
                            : msg.body();
                    sb.append("   内容预览：").append(preview).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("[EmailTools] 查看邮件失败: {}", e.getMessage(), e);
            return "❌ 查看邮件失败：" + e.getMessage() + "\n提示：请检查授权码是否正确，QQ邮箱/163邮箱需要使用授权码而非登录密码。";
        }
    }

    @Tool(description = "搜索邮件。按关键词或发件人搜索收件箱中的邮件")
    public String searchEmails(
            @ToolParam(description = "搜索关键词（匹配主题和正文），没有则传null", required = false) String keyword,
            @ToolParam(description = "发件人（邮箱地址或名字），没有则传null", required = false) String sender,
            @ToolParam(description = "最多返回几条，默认5") int maxResults) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) return "❌ 无法识别用户身份";

        EmailAccount account = emailAccountRepository.findByUserId(userId);
        if (account == null) {
            return "⚠️ 你还没有绑定邮箱，请先调用 bindEmail 绑定。";
        }

        if ((keyword == null || keyword.isBlank()) && (sender == null || sender.isBlank())) {
            return "⚠️ 请提供搜索关键词或发件人";
        }

        log.info("[EmailTools] 搜索邮件: userId={}, keyword={}, sender={}", userId, keyword, sender);

        try {
            int actualMax = maxResults <= 0 ? 5 : Math.min(maxResults, 20);
            List<EmailService.EmailMessage> messages = emailService.search(account, keyword, sender, actualMax);

            if (messages.isEmpty()) {
                return "🔍 没有找到匹配的邮件";
            }

            StringBuilder sb = new StringBuilder("🔍 找到 ").append(messages.size()).append(" 封匹配邮件：\n\n");
            for (int i = 0; i < messages.size(); i++) {
                EmailService.EmailMessage msg = messages.get(i);
                sb.append(i + 1).append(". ");
                sb.append("【").append(msg.subject()).append("】\n");
                sb.append("   发件人：").append(msg.from()).append("\n");
                sb.append("   时间：").append(msg.date()).append("\n");
                if (msg.body() != null && !msg.body().isBlank()) {
                    String preview = msg.body().length() > 200
                            ? msg.body().substring(0, 200) + "..."
                            : msg.body();
                    sb.append("   内容预览：").append(preview).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("[EmailTools] 搜索邮件失败: {}", e.getMessage(), e);
            return "❌ 搜索邮件失败：" + e.getMessage();
        }
    }

    private String maskEmail(String email) {
        int atIdx = email.indexOf("@");
        if (atIdx <= 2) return email;
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}
