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

    @Test void keepsKeelAndNamesTheDimensionOfADeletedConstraint() throws Exception {
        var first = PromptTexts.config(json.readTree("""
                {"temperature":0.2,"keel":{"sections":[
                  {"kind":"role","text":"你是值班员"},
                  {"kind":"task","text":"回答 {{question}}"},
                  {"kind":"constraint","text":"不要编造","dimension":"拒答 / 无法确认"}
                ],"variables":[{"name":"question","required":true}],
                "outputContract":{"type":"object"}}}
                """));
        assertThat(first.path("keel").path("sections").get(2).path("id").asText()).isEqualTo("c-1");
        assertThat(first.has("model")).isFalse();
        var second = PromptTexts.config(json.readTree("""
                {"keel":{"sections":[{"kind":"role","text":"你是值班员"}]}}
                """));
        var removed = PromptTexts.removedConstraints(first, second);
        assertThat(removed).hasSize(1);
        assertThat(removed.getFirst().get("dimension")).isEqualTo("拒答 / 无法确认");
        var compiled = PromptTexts.compileSections(first.path("keel").path("sections"), "text", null);
        assertThat(compiled.asText()).contains("不要编造").contains("{{question}}").doesNotContain("keel");
        assertThatThrownBy(() -> PromptTexts.applyVariables(compiled, "text", first.path("keel"), json.createObjectNode()))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.PROMPT_VARIABLE_MISSING);
        var filled = PromptTexts.applyVariables(compiled, "text", first.path("keel"), json.readTree("{\"question\":\"你好\"}"));
        assertThat(filled).isEqualTo(compiled.asText().replace("{{question}}", "你好"));
        assertThatThrownBy(() -> PromptTexts.config(json.readTree("{\"extra\":1}")))
                .isInstanceOf(KeelException.class);
    }

    @Test void diffsChatMessagesByLine() throws Exception {
        var from = json.readTree("[{\"role\":\"system\",\"content\":\"旧\\n行\"}]");
        var to = json.readTree("[{\"role\":\"system\",\"content\":\"新\\n行\"}]");
        var diff = PromptTexts.diff(from, "chat", to, "chat");
        assertThat(diff.get("removed")).isEqualTo(1);
        assertThat(diff.get("added")).isEqualTo(1);
    }
}
