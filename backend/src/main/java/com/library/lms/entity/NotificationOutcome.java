package com.library.lms.entity;

/** Whether a notification reached the mail server. */
public enum NotificationOutcome {

    /** Handed to the mail server without complaint. */
    SENT,

    /** The mail server refused it, or there was none to hand it to. */
    FAILED,

    /**
     * No delivery is configured on this deployment.
     *
     * <p>Distinct from FAILED on purpose: a development machine with no mail
     * server is not a broken production one, and a log full of failures would
     * hide the difference.</p>
     */
    NOT_CONFIGURED
}
