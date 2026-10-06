package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.anthropic.core.Timeout;
import com.library.lms.service.AiChatService;
import com.library.lms.service.AnthropicAiChatService;
import com.library.lms.service.GeminiAiChatService;
import com.library.lms.service.ScriptedAiChatService;

/**
 * Which assistant a deployment ends up running.
 *
 * <p>The default matters as much as the choice: development and CI must get the
 * scripted assistant without a key, and a deployment that asks for a provider
 * must be stopped at startup if it cannot reach one - an assistant that 503s
 * every question is worse than a deployment that will not start.</p>
 */
class ChatConfigTest {

    /** Test-only, never a real key. */
    private static final String API_KEY = "sk-ant-test-only-not-a-real-key";

    /** Test-only, never a real key. */
    private static final String GEMINI_KEY = "gemini-test-only-not-a-real-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class);

    // ---------- which assistant ----------

    @Test
    void theScriptedAssistantIsWhatADeploymentGetsWhenItAsksForNothing() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(AiChatService.class)
                .getBean(AiChatService.class)
                .isInstanceOf(ScriptedAiChatService.class));
    }

    @Test
    void theScriptedAssistantNeedsNoKey() {
        runner.withPropertyValues("chat.provider=scripted")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiChatService.class)
                        .isInstanceOf(ScriptedAiChatService.class));
    }

    @Test
    void namingAnthropicWithAKeyGetsClaude() {
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context)
                        .hasSingleBean(AiChatService.class)
                        .getBean(AiChatService.class)
                        .isInstanceOf(AnthropicAiChatService.class));
    }

    @Test
    void namingGeminiWithAKeyGetsGemini() {
        runner.withPropertyValues("chat.provider=gemini", "chat.gemini.api-key=" + GEMINI_KEY)
                .run(context -> assertThat(context)
                        .hasSingleBean(AiChatService.class)
                        .getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));
    }

    @Test
    void geminiIsReadWhateverItsCaseOrSpacing() {
        runner.withPropertyValues("chat.provider=  GeMiNi  ", "chat.gemini.api-key=" + GEMINI_KEY)
                .run(context -> assertThat(context).getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));
    }

    @Test
    void anthropicRemainsSelectableSoADeploymentCanBeMovedBack() {
        // The point of keeping it: switching provider is one setting, not a
        // deployment of different code.
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context).getBean(AiChatService.class)
                        .isInstanceOf(AnthropicAiChatService.class));

        runner.withPropertyValues("chat.provider=gemini", "chat.gemini.api-key=" + GEMINI_KEY)
                .run(context -> assertThat(context).getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));
    }

    @Test
    void onlyOneAssistantIsEverBuilt() {
        // Two providers configured, one bean: naming one must not leave the
        // other's client built and holding a connection pool.
        runner.withPropertyValues("chat.provider=gemini",
                        "chat.gemini.api-key=" + GEMINI_KEY,
                        "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context)
                        .hasSingleBean(AiChatService.class)
                        .getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));
    }

    @Test
    void theModelIsConfigurableAndDefaultsToAStableOne() {
        runner.withPropertyValues("chat.provider=gemini", "chat.gemini.api-key=" + GEMINI_KEY)
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("chat.provider=gemini",
                        "chat.gemini.api-key=" + GEMINI_KEY,
                        "chat.gemini.model=gemini-3.5-flash")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));

        // The default is a real model name rather than a placeholder.
        assertThat(GeminiAiChatService.DEFAULT_MODEL).startsWith("gemini-");
    }

    // ---------- how long a caller waits ----------

    @Test
    void theThinkingLevelIsConfigurableAndDefaultsToOneTheDefaultModelTakes() {
        assertThatCode(() -> ChatConfig.thinkingLevel(null)).doesNotThrowAnyException();
        assertThat(ChatConfig.thinkingLevel(null)).isEqualTo("minimal");
        assertThat(ChatConfig.thinkingLevel("   ")).isEqualTo("minimal");
        assertThat(ChatConfig.thinkingLevel("  LOW ")).isEqualTo("low");
        assertThat(ChatConfig.thinkingLevel("high")).isEqualTo("high");
    }

    @Test
    void movingBackToTheLargerModelIsTwoSettingsAndBothAreAccepted() {
        // The documented rollback: the larger model with the level it takes. It
        // has to start, or "set these two and restart" is not a rollback.
        runner.withPropertyValues("chat.provider=gemini",
                        "chat.gemini.api-key=" + GEMINI_KEY,
                        "chat.gemini.model=gemini-3.8-flash",
                        "chat.gemini.thinking-level=low")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "off", "zero", "lowest", "minimum", "0", "fast"})
    void aThinkingLevelNobodyRecognisesStopsStartup(String level) {
        // Rejected here rather than by the provider on every request: one
        // misspelt word would otherwise mean every question answered with a 503.
        assertThatThrownBy(() -> ChatConfig.thinkingLevel(level))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_THINKING_LEVEL");
    }

    @Test
    void aBadThinkingLevelStopsTheContextRatherThanBeingIgnored() {
        runner.withPropertyValues("chat.provider=gemini",
                        "chat.gemini.api-key=" + GEMINI_KEY,
                        "chat.gemini.thinking-level=turbo")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("GEMINI_THINKING_LEVEL"));
    }

    @Test
    void aConfiguredThinkingLevelReachesTheAssistant() {
        runner.withPropertyValues("chat.provider=gemini",
                        "chat.gemini.api-key=" + GEMINI_KEY,
                        "chat.gemini.model=gemini-3.6-flash",
                        "chat.gemini.thinking-level=medium")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiChatService.class)
                        .isInstanceOf(GeminiAiChatService.class));
    }

    @Test
    void theProviderNameIsReadWhateverItsCaseOrSpacing() {
        runner.withPropertyValues("chat.provider=  AnThRoPiC  ", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context).getBean(AiChatService.class)
                        .isInstanceOf(AnthropicAiChatService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gpt", "claude", "none"})
    void anAssistantNobodyImplementsStopsStartup(String provider) {
        runner.withPropertyValues("chat.provider=" + provider)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("CHAT_PROVIDER"));
    }

    // ---------- naming a provider without the means to reach it ----------

    @Test
    void namingAnthropicWithNoKeyStopsStartup() {
        runner.withPropertyValues("chat.provider=anthropic")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("ANTHROPIC_API_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankKeyStopsStartup(String key) {
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + key)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void namingGeminiWithNoKeyStopsStartup() {
        runner.withPropertyValues("chat.provider=gemini")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("GEMINI_API_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankGeminiKeyStopsStartup(String key) {
        runner.withPropertyValues("chat.provider=gemini", "chat.gemini.api-key=" + key)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void namingGeminiWhileHoldingOnlyTheAnthropicKeyStopsStartup() {
        // The half-finished switch. Starting here would 503 every question while
        // a perfectly good credential sat in the wrong variable.
        runner.withPropertyValues("chat.provider=gemini", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("GEMINI_API_KEY"));
    }

    @Test
    void namingAnthropicWhileHoldingOnlyTheGeminiKeyStopsStartup() {
        runner.withPropertyValues("chat.provider=anthropic", "chat.gemini.api-key=" + GEMINI_KEY)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("ANTHROPIC_API_KEY"));
    }

    // ---------- and no failure says what the key is ----------

    @Test
    void noStartupFailureEverQuotesEitherKey() {
        runner.withPropertyValues("chat.provider=openai",
                        "chat.gemini.api-key=" + GEMINI_KEY,
                        "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageNotContaining(GEMINI_KEY)
                        .hasMessageNotContaining(API_KEY));

        runner.withPropertyValues("chat.provider=gemini", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageNotContaining(API_KEY));
    }

    // ---------- how long the provider is given ----------

    @Test
    void theDefaultTimeoutsAreShortEnoughForSomeoneWaiting() {
        Timeout timeout = ChatConfig.timeouts("PT5S", "PT30S", "PT45S");

        assertThat(timeout.connect()).isEqualTo(Duration.ofSeconds(5));
        assertThat(timeout.read()).isEqualTo(Duration.ofSeconds(30));
        assertThat(timeout.request()).isEqualTo(Duration.ofSeconds(45));
        assertThat(timeout.write()).as("one short question, bounded like the read").isEqualTo(timeout.read());
    }

    @Test
    void theTimeoutsAreConfigurable() {
        Timeout timeout = ChatConfig.timeouts("1s", "2s", "3s");

        assertThat(timeout.connect()).isEqualTo(Duration.ofSeconds(1));
        assertThat(timeout.read()).isEqualTo(Duration.ofSeconds(2));
        assertThat(timeout.request()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void aTimeoutOfZeroOrLessIsRefusedRatherThanWaitingForEver() {
        assertThatThrownBy(() -> ChatConfig.timeouts("PT0S", "PT30S", "PT45S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_CONNECT_TIMEOUT");
        assertThatThrownBy(() -> ChatConfig.timeouts("PT5S", "-PT1S", "PT45S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_READ_TIMEOUT");
        assertThatThrownBy(() -> ChatConfig.timeouts("PT5S", "PT30S", "PT0S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_REQUEST_TIMEOUT");
    }

    @Test
    void aTimeoutThatIsNotADurationIsRefused() {
        assertThatThrownBy(() -> ChatConfig.timeouts("soon", "PT30S", "PT45S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_CONNECT_TIMEOUT");
    }

    @Test
    void aBadTimeoutStopsTheContextRatherThanBuildingAnAssistant() {
        runner.withPropertyValues(
                        "chat.provider=anthropic",
                        "chat.anthropic.api-key=" + API_KEY,
                        "chat.anthropic.read-timeout=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aPositiveTimeoutIsAccepted() {
        assertThatCode(() -> ChatConfig.timeouts("PT1S", "PT1S", "PT1S")).doesNotThrowAnyException();
    }

    // ---------- the key stays out of everything ----------

    @Test
    void theKeyIsNotInTheAssistantsDescription() {
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> {
                    AiChatService assistant = context.getBean(AiChatService.class);

                    assertThat(assistant.toString()).doesNotContain(API_KEY);
                    assertThat(assistant.name()).isEqualTo("anthropic");
                });
    }
}
