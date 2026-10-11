package com.smartlib.mail;

/** Abstraction over the actual delivery mechanism (SMTP in the app, a capturing fake in tests). */
public interface MailTransport {
    void send(MailMessage message) throws Exception;
}
