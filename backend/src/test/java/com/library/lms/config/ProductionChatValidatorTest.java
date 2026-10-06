package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a production instance must know before it may answer questions.
 *
 * <p>The rule: the assistant must be a real one, named explicitly, with the key
 * that belongs to it. The scripted assistant is the default everywhere else, and
 * this is what stops that default from reaching production, where an assistant
 * that answers "I cannot answer that yet" to everything looks exactly like one
 * that is working.</p>
 *
 * <p>Either real provider is allowed, and each is checked against its own key -
 * naming one while holding the other's credential is not being configured, and
 * it is the mistake a deployment makes while switching between them.</p>
 *
 * <p>Called directly rather than through a context, like the validators beside
 * it: what matters is the rule, not the wiring.</p>
 */
class ProductionChatValidatorTest {

    /** Test-only, never a real key. */
    private static final String API_KEY = "sk-ant-test-only-not-a-real-key";

    /** Test-only, never a real key. */
    private static final String GEMINI_KEY = "gemini-test-only-not-a-real-key";

    /** The settings as a production deployment on Gemini would have them. */
    private static void validateGemini(String provider, String geminiKey) {
        ProductionChatValidator.validate(provider, geminiKey, null);
    }

    /** The settings as a production deployment on Anthropic would have them. */
    private static void validateAnthropic(String provider, String anthropicKey) {
        ProductionChatValidator.validate(provider, null, anthropicKey);
    }

    @Test
    void geminiWithAKeyIsAccepted() {
        assertThatCode(() -> validateGemini("gemini", GEMINI_KEY)).doesNotThrowAnyException();
    }

    @Test
    void anthropicWithAKeyIsAccepted() {
        assertThatCode(() -> validateAnthropic("anthropic", API_KEY)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  AnThRoPiC  ", "ANTHROPIC", " anthropic"})
    void theProviderIsReadWhateverItsCaseOrSpacing(String provider) {
        assertThatCode(() -> validateAnthropic(provider, API_KEY)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  GeMiNi  ", "GEMINI", " gemini"})
    void geminiIsReadWhateverItsCaseOrSpacing(String provider) {
        assertThatCode(() -> validateGemini(provider, GEMINI_KEY)).doesNotThrowAnyException();
    }

    // ---------- the key must belong to the provider named ----------

    @Test
    void geminiWithOnlyTheAnthropicKeyIsRefused() {
        // The mistake a deployment makes halfway through switching provider: the
        // name changed and the credential did not. Starting would mean every
        // question answered with a 503.
        assertThatThrownBy(() -> ProductionChatValidator.validate("gemini", null, API_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void anthropicWithOnlyTheGeminiKeyIsRefused() {
        // And the same mistake made while switching back.
        assertThatThrownBy(() -> ProductionChatValidator.validate("anthropic", GEMINI_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    // ---------- the script must not follow a deployment into production ----------

    @Test
    void theScriptedAssistantIsRefused() {
        assertThatThrownBy(() -> validateAnthropic("scripted", API_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gemini")
                .hasMessageContaining("anthropic");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankProviderIsRefusedRatherThanDefaulted(String provider) {
        assertThatThrownBy(() -> validateAnthropic(provider, API_KEY))
                .as("the development default must not reach production by omission")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_PROVIDER");
    }

    @Test
    void anUnsetProviderIsRefused() {
        assertThatThrownBy(() -> validateAnthropic(null, API_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_PROVIDER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gpt", "claude", "none", "google", "bard", "gemini-pro"})
    void anAssistantNobodyImplementsIsRefused(String provider) {
        // "claude" and "gemini-pro" are here deliberately: they are model names,
        // not provider names, and a near miss must fail as plainly as a wild
        // guess.
        assertThatThrownBy(() -> ProductionChatValidator.validate(provider, GEMINI_KEY, API_KEY))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- named, but unusable ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anthropicWithoutAKeyIsRefused(String apiKey) {
        assertThatThrownBy(() -> validateAnthropic("anthropic", apiKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void geminiWithoutAKeyIsRefused(String apiKey) {
        assertThatThrownBy(() -> validateGemini("gemini", apiKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void anUnsetKeyIsRefused() {
        assertThatThrownBy(() -> validateAnthropic("anthropic", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
        assertThatThrownBy(() -> validateGemini("gemini", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void theProviderIsCheckedBeforeTheKey() {
        assertThatThrownBy(() -> ProductionChatValidator.validate("scripted", null, null))
                .as("a deployment is told about the first of its two problems, not the second")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anthropic")
                .hasMessageNotContaining("ANTHROPIC_API_KEY");
    }

    // ---------- and nothing is quoted back ----------

    @Test
    void noMessageEverQuotesEitherKey() {
        for (String[] settings : new String[][] {
                {null, GEMINI_KEY, API_KEY},
                {"scripted", GEMINI_KEY, API_KEY},
                {"anthropic", GEMINI_KEY, ""},
                {"gemini", "", API_KEY},
                {"openai", GEMINI_KEY, API_KEY}}) {
            assertThatThrownBy(() -> ProductionChatValidator.validate(settings[0], settings[1], settings[2]))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(API_KEY)
                            .doesNotContain(GEMINI_KEY));
        }
    }
}
