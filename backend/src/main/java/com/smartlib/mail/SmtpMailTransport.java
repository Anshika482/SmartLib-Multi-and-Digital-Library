package com.smartlib.mail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;

/**
 * Sends email over SMTP: a local catcher such as Mailpit in development, or a real provider (e.g. Gmail) that
 * delivers to real inboxes. With a real provider, addresses on demo/test domains (the seeded {@code @smartlib.demo}
 * accounts, {@code .test}/{@code .example} test users) are skipped: they cannot receive mail and would only bounce
 * back to the sending account.
 */
@Component
public class SmtpMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

    /** Reserved (RFC 2606 / 6761) or never-delegated TLDs used by demo and test accounts. */
    private static final Set<String> UNDELIVERABLE_TLDS = Set.of("test", "example", "invalid", "localhost", "local", "demo");
    private static final Set<String> RESERVED_DOMAINS = Set.of("example.com", "example.net", "example.org");

    private final JavaMailSender sender;
    private final AppProperties properties;
    private final boolean localCatcher;

    public SmtpMailTransport(JavaMailSender sender, AppProperties properties,
                             @Value("${spring.mail.host:localhost}") String host,
                             @Value("${spring.mail.port:25}") int port,
                             @Value("${spring.mail.username:}") String username,
                             @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean auth) {
        this.sender = sender;
        this.properties = properties;
        this.localCatcher = Set.of("localhost", "127.0.0.1", "::1").contains(host.trim().toLowerCase(Locale.ROOT));
        if (localCatcher) {
            log.info("Email: local mail catcher at {}:{} (all mail stays on this machine)", host, port);
            return;
        }
        log.info("Email: SMTP {}:{} delivers to real inboxes; demo/test addresses are skipped", host, port);
        if (auth && username.isBlank()) {
            log.warn("Email: MAIL_SMTP_AUTH=true but MAIL_USERNAME is empty, so sending will fail");
        }
        if (host.toLowerCase(Locale.ROOT).contains("gmail") && !username.equalsIgnoreCase(properties.mail().from())) {
            log.warn("Email: Gmail sends as the signed-in account; set MAIL_FROM to the same address as MAIL_USERNAME");
        }
    }

    @Override
    public void send(MailMessage message) throws Exception {
        if (!localCatcher && !deliverable(message.to())) {
            log.info("Email not sent to {}: demo/test domain that cannot receive mail ({})", mask(message.to()), safeSubject(message.subject()));
            return;
        }
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.mail().from(), properties.mail().fromName());
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.text(), message.html());
        sender.send(mime);
        log.info("Email accepted by SMTP server for {} ({})", mask(message.to()), safeSubject(message.subject()));
    }

    static boolean deliverable(String address) {
        int at = address.lastIndexOf('@');
        String domain = at < 0 ? "" : address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || RESERVED_DOMAINS.contains(domain)) {
            return false;
        }
        return !UNDELIVERABLE_TLDS.contains(domain.substring(domain.lastIndexOf('.') + 1));
    }

    /** Subjects can carry one-time codes ("… code 123456"); never let them reach the log. */
    static String safeSubject(String subject) {
        return subject == null ? "" : subject.replaceAll("\\d{4,}", "******");
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
