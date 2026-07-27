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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class EmailTools {

    private final EmailService emailService;
    private final EmailAccountRepository emailAccountRepository;
    private final UserContext userContext;
    private final Map<String, List<EmailService.EmailMessage>> lastFetchedCache = new ConcurrentHashMap<>();

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

    @Tool(description = "查看收件箱中最新的邮件列表，不做任何筛选。仅当用户想查看最近收到的邮件（不指定发件人、关键词）时调用。如果用户要查某人发的邮件或包含某关键词的邮件，应使用 searchEmails")
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
            lastFetchedCache.put(userId, messages);

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

    @Tool(description = "按发件人或关键词搜索邮件。当用户提到要查某人发的邮件、搜索某个主题的邮件时，必须直接调用此工具，不要先调用 readLatestEmails")
    public String searchEmails(
            @ToolParam(description = "搜索关键词（匹配主题和正文），如果用户只按发件人搜索则传null", required = false) String keyword,
            @ToolParam(description = "发件人的邮箱地址或名字。当用户说'查某人发的邮件'时，将人名填到这里", required = false) String sender,
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

    @Tool(description = "读取某封邮件的完整内容。先用 readLatestEmails 或 searchEmails 查看邮件列表，再用此工具按序号读取完整正文")
    public String readEmailDetail(
            @ToolParam(description = "邮件序号，从邮件列表中获取，最新的邮件序号为1") int index) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) return "❌ 无法识别用户身份";

        EmailAccount account = emailAccountRepository.findByUserId(userId);
        if (account == null) {
            return "⚠️ 你还没有绑定邮箱，请先调用 bindEmail 绑定。";
        }

        if (index < 1) {
            return "⚠️ 邮件序号无效，请输入大于0的序号";
        }

        log.info("[EmailTools] 读取邮件详情: userId={}, index={}", userId, index);

        try {
            EmailService.EmailMessage msg = emailService.fetchByIndex(account, index);

            StringBuilder sb = new StringBuilder();
            sb.append("📧 邮件详情（第 ").append(index).append(" 封）：\n\n");
            sb.append("主题：").append(msg.subject()).append("\n");
            sb.append("发件人：").append(msg.from()).append("\n");
            sb.append("收件人：").append(msg.to()).append("\n");
            sb.append("时间：").append(msg.date()).append("\n\n");
            sb.append("--- 正文 ---\n");
            sb.append(msg.body() != null ? msg.body() : "(无正文)");
            sb.append("\n--- 正文结束 ---");

            return sb.toString();

        } catch (IllegalArgumentException e) {
            return "⚠️ " + e.getMessage();
        } catch (Exception e) {
            log.error("[EmailTools] 读取邮件详情失败: {}", e.getMessage(), e);
            return "❌ 读取邮件详情失败：" + e.getMessage();
        }
    }

    private String maskEmail(String email) {
        int atIdx = email.indexOf("@");
        if (atIdx <= 2) return email;
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}
