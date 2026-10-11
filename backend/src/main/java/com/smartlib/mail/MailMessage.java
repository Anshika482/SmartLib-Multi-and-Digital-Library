package com.smartlib.mail;

public record MailMessage(String to, String subject, String html, String text) {
}
