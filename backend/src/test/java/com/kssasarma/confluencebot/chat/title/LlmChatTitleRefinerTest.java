package com.kssasarma.confluencebot.chat.title;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sanitiser is the whole safety story for generated titles: everything the model can return
 * that is not a title has to be rejected here, because the fallback — the clipped question — is
 * always a better outcome than a wrong name in the sidebar.
 */
class LlmChatTitleRefinerTest {

    @ParameterizedTest(name = "accepts {0} as {1}")
    @CsvSource({
            "'Password reset process', 'Password reset process'",
            "'\"Password reset process\"', 'Password reset process'",
            "'Title: Password reset process', 'Password reset process'",
            "'  Password reset process.  ', 'Password reset process'",
            "'**Password reset process**', 'Password reset process'",
            "'Password reset process\nExplanation follows', 'Password reset process'",
    })
    void stripsTheWrappingModelsAddBack(String raw, String expected) {
        assertThat(LlmChatTitleRefiner.sanitise(raw)).contains(expected);
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "NONE",
            "none",
            "ab",
            "I'm sorry, but I cannot determine a topic from this exchange without more context",
            "To reset your password you should navigate to the login page and then choose forgot",
    })
    void rejectsAnythingThatIsNotATitle(String raw) {
        assertThat(LlmChatTitleRefiner.sanitise(raw)).isEmpty();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(LlmChatTitleRefiner.sanitise(null)).isEmpty();
        assertThat(LlmChatTitleRefiner.sanitise("   ")).isEmpty();
        assertThat(LlmChatTitleRefiner.sanitise("\"\"")).isEmpty();
    }
}
