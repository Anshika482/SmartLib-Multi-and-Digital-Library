package com.library.lms.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Lets work happen off the request thread.
 *
 * <p>Without {@code @EnableAsync} an {@code @Async} method is simply called
 * normally, on the caller's thread, with no warning - which would mean every
 * book issued waited on an SMTP handshake while looking as though it did not.
 *
 * <p><b>The pool is small and bounded on purpose.</b> Notifications are the only
 * thing on it, they are not urgent, and an unbounded queue in front of an
 * unreachable mail server is a memory leak with a delay on it. When the queue
 * fills, {@link ThreadPoolExecutor.CallerRunsPolicy} hands the work back to the
 * caller: the request slows down rather than the notification being dropped, and
 * the slowdown is the back-pressure that stops the queue growing.
 *
 * <p>Nothing else in the application is asynchronous today. This exists for the
 * notifications and is deliberately not a general-purpose thread pool.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * The executor {@code @Async} uses.
     *
     * <p>Named {@code taskExecutor} because that is the name Spring looks for by
     * default, so no {@code @Async("...")} qualifier is needed anywhere and
     * nothing can silently fall back to a different pool.</p>
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);

        // Named so a thread dump says what the thread was for.
        executor.setThreadNamePrefix("notify-");

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // On shutdown, finish what is queued rather than dropping it - a
        // notification half-way through a deploy is still worth sending. Bounded
        // so a hung SMTP connection cannot hold the process open indefinitely.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);

        executor.initialize();
        return executor;
    }
}
