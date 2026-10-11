package com.smartlib.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmtpMailTransportTest {

    @Test
    void realAddressesAreDeliverable() {
        assertThat(SmtpMailTransport.deliverable("reader@gmail.com")).isTrue();
        assertThat(SmtpMailTransport.deliverable("staff@college.ac.in")).isTrue();
        assertThat(SmtpMailTransport.deliverable("Someone@Outlook.COM")).isTrue();
    }

    @Test
    void demoAndTestDomainsAreSkippedWithARealProvider() {
        assertThat(SmtpMailTransport.deliverable("member@smartlib.demo")).isFalse();
        assertThat(SmtpMailTransport.deliverable("member.1@e2e.smartlib.test")).isFalse();
        assertThat(SmtpMailTransport.deliverable("someone@example.com")).isFalse();
        assertThat(SmtpMailTransport.deliverable("x@host.invalid")).isFalse();
        assertThat(SmtpMailTransport.deliverable("no-at-sign")).isFalse();
    }

    @Test
    void codesNeverReachTheLog() {
        assertThat(SmtpMailTransport.safeSubject("Verify your SmartLib email — code 482913")).isEqualTo("Verify your SmartLib email — code ******");
        assertThat(SmtpMailTransport.safeSubject("Due today: Deep Work")).isEqualTo("Due today: Deep Work");
    }
}
