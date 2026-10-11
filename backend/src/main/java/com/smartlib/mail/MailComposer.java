package com.smartlib.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.smartlib.common.config.AppProperties;

/** Builds branded HTML + plain-text emails from the single {@code mail/standard} template. */
@Component
public class MailComposer {

    private final TemplateEngine templates;
    private final AppProperties properties;

    public MailComposer(TemplateEngine templates, AppProperties properties) {
        this.templates = templates;
        this.properties = properties;
    }

    public static final class Builder {
        private final String to;
        private String subject;
        private String title;
        private String preheader;
        private String greeting;
        private final List<String> paragraphs = new ArrayList<>();
        private final List<Map<String, String>> facts = new ArrayList<>();
        private String otp;
        private String ctaLabel;
        private String ctaUrl;
        private String securityNote;
        private String footnote;

        private Builder(String to) {
            this.to = to;
        }

        public Builder subject(String v) { this.subject = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder preheader(String v) { this.preheader = v; return this; }
        public Builder greeting(String v) { this.greeting = v; return this; }
        public Builder paragraph(String v) { if (v != null) { this.paragraphs.add(v); } return this; }
        public Builder fact(String label, String value) { if (value != null) { this.facts.add(Map.of("label", label, "value", value)); } return this; }
        public Builder otp(String v) { this.otp = v; return this; }
        public Builder cta(String label, String url) { this.ctaLabel = label; this.ctaUrl = url; return this; }
        public Builder securityNote(String v) { this.securityNote = v; return this; }
        public Builder footnote(String v) { this.footnote = v; return this; }
    }

    public Builder to(String email) {
        return new Builder(email);
    }

    public String link(String path) {
        String base = properties.frontendBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    public MailMessage build(Builder b) {
        Context ctx = new Context();
        ctx.setVariable("title", b.title);
        ctx.setVariable("preheader", b.preheader == null ? b.title : b.preheader);
        ctx.setVariable("greeting", b.greeting);
        ctx.setVariable("paragraphs", b.paragraphs);
        ctx.setVariable("facts", b.facts);
        ctx.setVariable("otp", b.otp);
        ctx.setVariable("ctaLabel", b.ctaLabel);
        ctx.setVariable("ctaUrl", b.ctaUrl);
        ctx.setVariable("securityNote", b.securityNote);
        ctx.setVariable("footnote", b.footnote);
        ctx.setVariable("logoUrl", link("/brand/smartlib-logo.png"));
        ctx.setVariable("siteUrl", link("/"));
        String html = templates.process("mail/standard", ctx);
        return new MailMessage(b.to, b.subject, html, text(b));
    }

    private static String text(Builder b) {
        StringBuilder sb = new StringBuilder();
        sb.append("SmartLib — Intelligence in Every Chapter\n\n");
        if (b.greeting != null) {
            sb.append(b.greeting).append("\n\n");
        }
        if (b.title != null) {
            sb.append(b.title).append("\n\n");
        }
        for (String p : b.paragraphs) {
            sb.append(p).append("\n\n");
        }
        if (b.otp != null) {
            sb.append("Your verification code: ").append(b.otp).append("\n\n");
        }
        for (Map<String, String> f : b.facts) {
            sb.append(f.get("label")).append(": ").append(f.get("value")).append('\n');
        }
        if (!b.facts.isEmpty()) {
            sb.append('\n');
        }
        if (b.ctaUrl != null) {
            sb.append(b.ctaLabel).append(": ").append(b.ctaUrl).append("\n\n");
        }
        if (b.securityNote != null) {
            sb.append(b.securityNote).append("\n\n");
        }
        if (b.footnote != null) {
            sb.append(b.footnote).append('\n');
        }
        return sb.toString();
    }
}
