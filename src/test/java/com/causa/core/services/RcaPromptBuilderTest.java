package com.causa.core.services;

import com.causa.config.LlmConfigCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RcaPromptBuilder profile note")
class RcaPromptBuilderTest {

    @Mock
    private LlmConfigCache llmConfigCache;

    @Test
    @DisplayName("renders the profile note and does not treat an excluded server as a gap instruction")
    void rendersProfileNote() {
        when(llmConfigCache.getActive()).thenReturn(Optional.empty());
        RcaPromptBuilder builder = new RcaPromptBuilder(
                new PromptTemplateLoader("/prompts/rca-prompt-template.yml"), llmConfigCache);

        String note = RcaPromptBuilder.profileNote(
                Set.of("kubernetes", "kruize"), List.of("cryostat", "kubernetes", "kruize"));
        String prompt = builder.buildPrompt(null, "POD_STATUS body", note);

        assertThat(note).isEqualTo(
                "Data sources in scope for this analysis: kruize, kubernetes. "
                        + "Intentionally excluded: cryostat. Do not report excluded sources as missing data.");
        assertThat(prompt).contains(note);
        assertThat(prompt).contains("POD_STATUS body");
        assertThat(prompt).contains("do not report CRYOSTAT_DISCOVERY or CRYOSTAT_ANALYSIS as missing");
        assertThat(prompt).doesNotContain("{{profile_note}}");
        assertThat(prompt).doesNotContain("{{context}}");
    }

    @Test
    @DisplayName("omits the exclusion sentence when every server is in scope")
    void omitsExclusionWhenNothingIsExcluded() {
        String note = RcaPromptBuilder.profileNote(Set.of("kubernetes"), List.of("kubernetes"));
        assertThat(note).isEqualTo("Data sources in scope for this analysis: kubernetes.");
        assertThat(note).doesNotContain("Intentionally excluded");
    }
}
