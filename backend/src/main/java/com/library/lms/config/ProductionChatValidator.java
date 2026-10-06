package com.library.lms.config;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a production instance whose assistant is the script.
 *
 * <p><b>Why this is worth failing over.</b> The scripted assistant answers from
 * a keyword list. It is honest about not knowing things, so nobody is misled
 * the way a sandbox payment misleads - but a deployment left on it has an
 * assistant in name only, answering "I cannot answer that yet" to most of what
 * anyone asks, and nothing inside the application reports that as a fault.
 * Every request succeeds. Startup is the one place the difference can be
 * noticed, so that is where it is caught.</p>
 *
 * <p><b>The default stays the script.</b> Development and CI have no provider
 * key and should not need one: they run on the default, and this validator only
 * exists under the prod profile. It is what stops that default from following a
 * deployment into production unnoticed.</p>
 *
 * <p><b>A key is required with it, and it must be that provider's key.</b>
 * Naming a provider without one would start an instance that answers every
 * question with a 503, and naming one while setting the other's key is the same
 * fault wearing a disguise. {@code ChatConfig} already refuses both wherever it
 * runs; this says the same thing about the settings themselves, before anything
 * is built, so a production deployment fails on the first of its problems rather
 * than the second.</p>
 *
 * <p><b>Either real provider is allowed.</b> Which one is a deployment's choice,
 * and keeping both accepted is what makes moving back to the other a change of
 * one setting rather than a change of code.</p>
 *
 * <p>Only under the prod profile, and run as a {@code BeanFactoryPostProcessor}
 * for the same reason as the validators beside it: before anything reads these
 * settings. No message quotes a value - the key is one of them.</p>
 */
@Configuration
@Profile("prod")
public class ProductionChatValidator {

    static final String PROVIDER_PROPERTY = "chat.provider";

    static final String API_KEY_PROPERTY = "chat.anthropic.api-key";

    static final String GEMINI_API_KEY_PROPERTY = "chat.gemini.api-key";

    static final String ANTHROPIC = "anthropic";

    static final String GEMINI = "gemini";

    /** The assistants a production deployment may answer with, and no others. */
    static final List<String> REQUIRED_PROVIDERS = List.of(GEMINI, ANTHROPIC);

    @Bean
    static BeanFactoryPostProcessor productionChatCheck(ConfigurableEnvironment environment) {
        return beanFactory -> validate(
                environment.getProperty(PROVIDER_PROPERTY),
                environment.getProperty(GEMINI_API_KEY_PROPERTY),
                environment.getProperty(API_KEY_PROPERTY));
    }

    /**
     * Requires a real assistant, named explicitly, with the key that belongs to
     * it.
     *
     * @throws IllegalStateException if the provider is missing, blank or not one
     *                               of the real ones, or if that provider's key
     *                               is missing
     */
    static void validate(String provider, String geminiApiKey, String anthropicApiKey) {
        String named = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);

        if (named.isEmpty()) {
            throw new IllegalStateException(PROVIDER_PROPERTY + " is not set. Set CHAT_PROVIDER to "
                    + GEMINI + " or " + ANTHROPIC + "; the scripted assistant answers from a keyword list and is"
                    + " meant for development and CI, so it must not be reached by leaving this unset in"
                    + " production.");
        }

        if (!REQUIRED_PROVIDERS.contains(named)) {
            throw new IllegalStateException(PROVIDER_PROPERTY + " must be " + GEMINI + " or " + ANTHROPIC
                    + " in production. The scripted assistant answers from a keyword list, and every one of its"
                    + " answers looks like a working assistant's.");
        }

        // The key that belongs to the provider named, not whichever key happens
        // to be set. Holding the other one is not being configured.
        if (GEMINI.equals(named)) {
            requireKey(geminiApiKey, GEMINI_API_KEY_PROPERTY, "GEMINI_API_KEY");
        } else {
            requireKey(anthropicApiKey, API_KEY_PROPERTY, "ANTHROPIC_API_KEY");
        }
    }

    private static void requireKey(String apiKey, String property, String variable) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(property + " is not set. Set " + variable + " to the key for"
                    + " the assistant's provider; without it every question is answered with a 503.");
        }
    }
}
