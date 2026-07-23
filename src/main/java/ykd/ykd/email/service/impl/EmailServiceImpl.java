package ykd.ykd.email.service.impl;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.email.model.EmailAccount;
import ykd.ykd.email.service.EmailService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private static final int MAX_BODY_LENGTH = 2000;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @Override
    public List<EmailMessage> fetchLatest(EmailAccount account, int count) {
        List<EmailMessage> results = new ArrayList<>();
        Folder inbox = null;
        Store store = null;

        try {
            store = connectStore(account);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int total = inbox.getMessageCount();
            int start = Math.max(1, total - count + 1);
            Message[] messages = inbox.getMessages(start, total);

            for (Message msg : messages) {
                results.add(toEmailMessage(msg));
            }

            log.info("[EmailService] 获取最新邮件成功: count={}", results.size());
            return results;

        } catch (Exception e) {
            log.error("[EmailService] 获取邮件失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取邮件失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    @Override
    public List<EmailMessage> search(EmailAccount account, String keyword, String sender, int maxResults) {
        List<EmailMessage> results = new ArrayList<>();
        Folder inbox = null;
        Store store = null;

        try {
            store = connectStore(account);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            SearchTerm searchTerm = buildSearchTerm(keyword, sender);
            Message[] messages = inbox.search(searchTerm);

            int start = Math.max(0, messages.length - maxResults);
            for (int i = start; i < messages.length; i++) {
                results.add(toEmailMessage(messages[i]));
            }

            log.info("[EmailService] 搜索邮件成功: keyword={}, sender={}, found={}", keyword, sender, results.size());
            return results;

        } catch (Exception e) {
            log.error("[EmailService] 搜索邮件失败: {}", e.getMessage(), e);
            throw new RuntimeException("搜索邮件失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    private Store connectStore(EmailAccount account) throws MessagingException {
        Properties props = new Properties();
        String protocol = account.ssl() ? "imaps" : "imap";
        props.setProperty("mail.store.protocol", protocol);
        props.setProperty("mail." + protocol + ".host", account.imapHost());
        props.setProperty("mail." + protocol + ".port", String.valueOf(account.imapPort()));

        if (account.ssl()) {
            props.setProperty("mail.imaps.ssl.trust", "*");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(account.imapHost(), account.imapPort(), account.email(), account.password());
        return store;
    }

    private SearchTerm buildSearchTerm(String keyword, String sender) {
        List<SearchTerm> terms = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            terms.add(new OrTerm(
                    new SubjectTerm(keyword),
                    new BodyTerm(keyword)
            ));
        }
        if (sender != null && !sender.isBlank()) {
            terms.add(new FromStringTerm(sender));
        }

        if (terms.isEmpty()) {
            return new SubjectTerm("");
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        return new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    private EmailMessage toEmailMessage(Message msg) throws Exception {
        String from = "";
        if (msg.getFrom() != null && msg.getFrom().length > 0) {
            Address addr = msg.getFrom()[0];
            if (addr instanceof InternetAddress ia) {
                from = ia.getPersonal() != null
                        ? ia.getPersonal() + " <" + ia.getAddress() + ">"
                        : ia.getAddress();
            } else {
                from = addr.toString();
            }
        }

        String to = "";
        if (msg.getRecipients(Message.RecipientType.TO) != null) {
            Address[] recipients = msg.getRecipients(Message.RecipientType.TO);
            StringBuilder sb = new StringBuilder();
            for (Address addr : recipients) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(addr.toString());
            }
            to = sb.toString();
        }

        String subject = msg.getSubject() != null ? msg.getSubject() : "(无主题)";
        String date = msg.getSentDate() != null
                ? DATE_FORMAT.format(msg.getSentDate())
                : "(未知时间)";

        String body = extractBody(msg);

        return new EmailMessage(from, to, subject, date, body);
    }

    private String extractBody(Part part) throws Exception {
        String contentType = part.getContentType();

        if (contentType.startsWith("text/plain")) {
            Object content = part.getContent();
            String text = content.toString();
            return text.length() > MAX_BODY_LENGTH
                    ? text.substring(0, MAX_BODY_LENGTH) + "...(已截断)"
                    : text;
        }

        if (contentType.startsWith("text/html")) {
            Object content = part.getContent();
            String html = content.toString();
            String text = html.replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&amp;", "&")
                    .replaceAll("\\s+", " ")
                    .trim();
            return text.length() > MAX_BODY_LENGTH
                    ? text.substring(0, MAX_BODY_LENGTH) + "...(已截断)"
                    : text;
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String result = extractBody(bodyPart);
                if (result != null && !result.isBlank()) {
                    return result;
                }
            }
        }

        return "(无法提取正文内容)";
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) {}
        try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
    }
}
