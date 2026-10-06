package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The ceiling on a public endpoint that costs money per call.
 */
class PublicChatRateLimiterTest {

    @Test
    void aClientMayAskUpToTheLimit() {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(3, 300);

        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
    }

    @Test
    void theOneAfterTheLimitIsRefused() {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(2, 300);
        limiter.tryAcquire("1.2.3.4");
        limiter.tryAcquire("1.2.3.4");

        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();
    }

    @Test
    void oneClientRunningOutDoesNotAffectAnother() {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(1, 300);
        limiter.tryAcquire("1.2.3.4");

        assertThat(limiter.tryAcquire("1.2.3.4")).as("the one who spent it").isFalse();
        assertThat(limiter.tryAcquire("5.6.7.8")).as("an unrelated visitor").isTrue();
    }

    @Test
    void theWindowRollsOverAndTheClientMayAskAgain() throws Exception {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(1, 1);
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();

        Thread.sleep(1100);

        assertThat(limiter.tryAcquire("1.2.3.4")).as("a new window").isTrue();
    }

    @Test
    void aMissingAddressIsOneSharedBucketRatherThanAWayPast() {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(1, 300);

        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire(null)).as("not waved through").isFalse();
        assertThat(limiter.tryAcquire("  ")).as("blank shares the same bucket").isFalse();
    }

    @Test
    void aRefusalCanSayHowLongToWait() {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(1, 300);
        limiter.tryAcquire("1.2.3.4");

        assertThat(limiter.secondsUntilReset("1.2.3.4")).isBetween(1L, 300L);
        assertThat(limiter.secondsUntilReset("9.9.9.9")).as("a client with no window waits for nothing").isZero();
    }

    @Test
    void anAbsurdConfigurationIsClampedRatherThanDisablingTheLimit() {
        PublicChatRateLimiter limiter = new PublicChatRateLimiter(0, 0);

        assertThat(limiter.limit()).isGreaterThanOrEqualTo(1);
        assertThat(limiter.windowSeconds()).isGreaterThanOrEqualTo(1);
    }
}
