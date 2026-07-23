package ykd.ykd.email.service;

import ykd.ykd.email.model.EmailAccount;

import java.util.List;

public interface EmailService {

    List<EmailMessage> fetchLatest(EmailAccount account, int count);

    List<EmailMessage> search(EmailAccount account, String keyword, String sender, int maxResults);

    record EmailMessage(
            String from,
            String to,
            String subject,
            String date,
            String body
    ) {
    }
}
