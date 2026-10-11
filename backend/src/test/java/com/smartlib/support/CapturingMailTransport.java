package com.smartlib.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.smartlib.mail.MailMessage;
import com.smartlib.mail.MailTransport;

/** Test double that records every email instead of sending it. */
public class CapturingMailTransport implements MailTransport {

    private final List<MailMessage> sent = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void send(MailMessage message) {
        sent.add(message);
    }

    public List<MailMessage> all() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    public Optional<MailMessage> latestTo(String email) {
        List<MailMessage> copy = all();
        for (int i = copy.size() - 1; i >= 0; i--) {
            if (copy.get(i).to().equalsIgnoreCase(email)) {
                return Optional.of(copy.get(i));
            }
        }
        return Optional.empty();
    }

    public String latestOtp(String email) {
        String text = latestTo(email).orElseThrow(() -> new AssertionError("No email to " + email)).text();
        Matcher m = Pattern.compile("Your verification code: (\\d{6})").matcher(text);
        if (!m.find()) {
            throw new AssertionError("No OTP in email: " + text);
        }
        return m.group(1);
    }

    public String latestToken(String email, String path) {
        String text = latestTo(email).orElseThrow(() -> new AssertionError("No email to " + email)).text();
        Matcher m = Pattern.compile(Pattern.quote(path) + "\\?token=([A-Za-z0-9_-]+)").matcher(text);
        if (!m.find()) {
            throw new AssertionError("No token for " + path + " in email: " + text);
        }
        return m.group(1);
    }
}
