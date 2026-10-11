package com.smartlib.auth;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smartlib.common.config.AppProperties;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.mail.MailComposer;
import com.smartlib.mail.MailService;
import com.smartlib.user.UserRepository;

/** Sends secret-bearing account emails after the transaction commits. Secrets are never logged or stored. */
@Component
public class AuthMailListener {

    private final MailService mail;
    private final MailComposer composer;
    private final AppProperties properties;
    private final UserRepository users;

    public AuthMailListener(MailService mail, MailComposer composer, AppProperties properties, UserRepository users) {
        this.mail = mail;
        this.composer = composer;
        this.properties = properties;
        this.users = users;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVerification(DomainEvents.VerificationEmailRequested e) {
        long minutes = properties.verification().otpTtl().toMinutes();
        String linkValidity = human(properties.verification().linkTtl());
        mail.sendNow(composer.build(composer.to(e.email())
                .subject("Verify your SmartLib email — code " + e.otp())
                .title("Verify your email address")
                .preheader("Your SmartLib verification code is inside. It expires in " + minutes + " minutes.")
                .greeting("Hi " + firstName(e.fullName()) + ",")
                .paragraph("Welcome to SmartLib! To finish setting up your account, enter this 6-digit code on the verification screen:")
                .otp(e.otp())
                .paragraph("Or verify with one click using the secure button below.")
                .cta("Verify my email", composer.link("/verify-email?token=" + e.linkToken()))
                .fact("Code expires in", minutes + " minutes")
                .fact("Link expires in", linkValidity)
                .securityNote("For your security: SmartLib staff will never ask you for this code. If you did not create a SmartLib "
                        + "account, you can safely ignore this email — no account will be activated.")
                .footnote("Each code and link can be used only once. Requesting a new email cancels the previous code and link.")));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordReset(DomainEvents.PasswordResetRequested e) {
        mail.sendNow(composer.build(composer.to(e.email())
                .subject("Reset your SmartLib password")
                .title("Password reset requested")
                .greeting("Hi " + firstName(e.fullName()) + ",")
                .paragraph("We received a request to reset the password for your SmartLib account. Use the button below to choose a new password.")
                .cta("Reset password", composer.link("/reset-password?token=" + e.token()))
                .fact("Link expires in", human(properties.passwordReset().ttl()))
                .securityNote("If you did not request this, you can ignore this email. Your password will not change.")
                .footnote("This link can be used only once.")));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAdminInvite(DomainEvents.AdminInvited e) {
        mail.sendNow(composer.build(composer.to(e.email())
                .subject("You're invited to administer " + e.libraryName() + " on SmartLib")
                .title("Your SmartLib administrator invitation")
                .greeting("Hi " + firstName(e.fullName()) + ",")
                .paragraph("A SmartLib platform administrator has created an administrator account for you at " + e.libraryName() + ".")
                .paragraph("Set your password to activate the account.")
                .cta("Set my password", composer.link("/accept-invite?token=" + e.token()))
                .fact("Library", e.libraryName())
                .fact("Invitation expires in", "72 hours")
                .securityNote("If you were not expecting this invitation, ignore this email.")));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordChanged(DomainEvents.PasswordChanged e) {
        users.findById(e.userId()).ifPresent(u -> mail.enqueue(composer.build(composer.to(u.getEmail())
                .subject("Your SmartLib password was changed")
                .title("Password changed")
                .greeting("Hi " + firstName(u.getFullName()) + ",")
                .paragraph("The password for your SmartLib account was just changed and you were signed out of other devices.")
                .securityNote("If this wasn't you, reset your password immediately using 'Forgot password' on the login page.")
                .cta("Go to SmartLib", composer.link("/login")))));
    }

    static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }

    static String human(Duration d) {
        if (d.toHours() >= 1 && d.toMinutesPart() == 0) {
            return d.toHours() + (d.toHours() == 1 ? " hour" : " hours");
        }
        return d.toMinutes() + " minutes";
    }
}
