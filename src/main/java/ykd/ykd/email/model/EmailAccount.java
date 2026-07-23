package ykd.ykd.email.model;

public record EmailAccount(
        String email,
        String password,
        String imapHost,
        int imapPort,
        boolean ssl
) {
}