package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for direct HTML-to-Markdown passthrough rendering. */
class HtmlPassthroughContractTest {

    @Test
    @DisplayName("html passthrough preserves headings, tables, code fences, and decoded text")
    void htmlPassthroughPreservesUsefulMarkdownStructure() {
        String html = """
                <h1>Candidate &amp; Evidence</h1>
                <p>Works with <strong>tables</strong>.</p>
                <table><tr><th>Company</th><th>Role</th></tr><tr><td>Acme</td><td>Engineer</td></tr></table>
                <pre><code>score = 42</code></pre>
                """;

        String markdown = TrustHtml.toMarkdownPassthrough(html);

        assertThat(markdown).contains("# Candidate & Evidence");
        assertThat(markdown).contains("Works with **tables**.");
        assertThat(markdown).contains("Company | Role");
        assertThat(markdown).contains("Acme | Engineer");
        assertThat(markdown).contains("```");
        assertThat(markdown).contains("score = 42");
    }

    @Test
    @DisplayName("html passthrough rejects null input")
    void rejectsNullInput() {
        assertThatThrownBy(() -> TrustHtml.toMarkdownPassthrough(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("html");
    }
}
