package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CitationSourceTest {

    @Test
    void acceptsTrustDocumentSourceIdentity() {
        var source = new CitationSource("sha256:abc", "u1");

        assertThat(source.docId()).isEqualTo("sha256:abc");
        assertThat(source.unitId()).isEqualTo("u1");
    }

    @Test
    void rejectsNullDocId() {
        assertThatThrownBy(() -> new CitationSource(null, "u1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("docId");
    }

    @Test
    void rejectsNullUnitId() {
        assertThatThrownBy(() -> new CitationSource("sha256:abc", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("unitId");
    }

    @Test
    void rejectsBlankDocId() {
        assertThatThrownBy(() -> new CitationSource(" ", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docId");
    }

    @Test
    void rejectsBlankUnitId() {
        assertThatThrownBy(() -> new CitationSource("sha256:abc", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitId");
    }
}
