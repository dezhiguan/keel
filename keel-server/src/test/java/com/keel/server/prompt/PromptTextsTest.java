package com.keel.server.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTextsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void hashesTextAndRejectsAModelInConfig() throws Exception {
        var prompt = json.readTree("\"只回答 {{question}}\"");
        assertThat(PromptTexts.sha256(prompt, "text")).startsWith("sha256:");
        assertThat(PromptTexts.sameHash(PromptTexts.sha256(prompt, "text"), prompt, "text")).isTrue();
        assertThat(PromptTexts.variables(prompt, "text")).containsExactly("question");
        assertThatThrownBy(() -> PromptTexts.config(json.readTree("{\"model\":\"qwen-plus\"}")))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
    }

    @Test void diffsChatMessagesByLine() throws Exception {
        var from = json.readTree("[{\"role\":\"system\",\"content\":\"旧\\n行\"}]");
        var to = json.readTree("[{\"role\":\"system\",\"content\":\"新\\n行\"}]");
        var diff = PromptTexts.diff(from, "chat", to, "chat");
        assertThat(diff.get("removed")).isEqualTo(1);
        assertThat(diff.get("added")).isEqualTo(1);
    }
}
