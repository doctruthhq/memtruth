package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link DocumentMetadata}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code sourceFilename} is non-null and non-blank (whitespace-only strings are
 *       rejected — a blank filename is never a meaningful source identifier).
 *   <li>{@code pageCount >= 1} — every parsed document has at least one page.
 *   <li>{@code sourcePublishedAt} is a non-null {@link Optional}; callers must pass
 *       {@code Optional.empty()} explicitly rather than {@code null}.
 * </ul>
 */
class DocumentMetadataTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a typical filename, page count, and an empty published-at")
        void typicalEmptyPublishedAt() {
            var meta = new DocumentMetadata("tender-2025.pdf", 42, Optional.empty());

            assertThat(meta.sourceFilename()).isEqualTo("tender-2025.pdf");
            assertThat(meta.pageCount()).isEqualTo(42);
            assertThat(meta.sourcePublishedAt()).isEmpty();
        }

        @Test
        @DisplayName("accepts a present sourcePublishedAt and round-trips through equality")
        void presentPublishedAtRoundTrips() {
            var now = Instant.parse("2026-05-07T00:00:00Z");
            var a = new DocumentMetadata("tender.pdf", 1, Optional.of(now));
            var b = new DocumentMetadata("tender.pdf", 1, Optional.of(now));

            assertThat(a.sourcePublishedAt()).contains(now);
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("accepts the minimum valid pageCount of 1")
        void minimumPageCount() {
            var meta = new DocumentMetadata("one-pager.pdf", 1, Optional.empty());

            assertThat(meta.pageCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Optional.empty() and Optional.of(...) are distinct under equals")
        void emptyAndPresentDiffer() {
            var empty = new DocumentMetadata("a.pdf", 1, Optional.empty());
            var present = new DocumentMetadata("a.pdf", 1, Optional.of(Instant.EPOCH));

            assertThat(empty).isNotEqualTo(present);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null sourceFilename with NullPointerException")
        void nullFilename() {
            assertThatThrownBy(() -> new DocumentMetadata(null, 1, Optional.empty()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourceFilename");
        }

        @Test
        @DisplayName("rejects an empty sourceFilename with IllegalArgumentException")
        void emptyFilename() {
            assertThatThrownBy(() -> new DocumentMetadata("", 1, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceFilename");
        }

        @Test
        @DisplayName("rejects a whitespace-only sourceFilename (spaces) with IllegalArgumentException")
        void whitespaceFilenameSpaces() {
            assertThatThrownBy(() -> new DocumentMetadata("   ", 1, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceFilename");
        }

        @Test
        @DisplayName("rejects a whitespace-only sourceFilename (tabs and newlines) with IllegalArgumentException")
        void whitespaceFilenameTabs() {
            assertThatThrownBy(() -> new DocumentMetadata("\t\n", 1, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceFilename");
        }

        @Test
        @DisplayName("rejects pageCount of 0 with IllegalArgumentException")
        void pageCountZero() {
            assertThatThrownBy(() -> new DocumentMetadata("a.pdf", 0, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageCount");
        }

        @Test
        @DisplayName("rejects negative pageCount with IllegalArgumentException")
        void pageCountNegative() {
            assertThatThrownBy(() -> new DocumentMetadata("a.pdf", -1, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageCount");
        }

        @Test
        @DisplayName("rejects null sourcePublishedAt with NullPointerException (callers must pass Optional.empty())")
        void nullPublishedAt() {
            assertThatThrownBy(() -> new DocumentMetadata("a.pdf", 1, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourcePublishedAt");
        }
    }
}
