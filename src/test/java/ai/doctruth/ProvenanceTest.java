package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link Provenance}.
 *
 * <p>Bi-temporal provenance per PRD section 4.3: {@code extractedAt} is when this
 * library produced the value; {@code sourcePublishedAt} is when the source document
 * was authored / published (optional — many sources have no reliable publication date).
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code model} is non-null and non-blank.
 *   <li>{@code modelVersion} is non-null and non-blank.
 *   <li>{@code extractedAt} is non-null.
 *   <li>{@code sourcePublishedAt} is an {@link Optional} and the {@code Optional} itself
 *       is non-null. Callers pass {@link Optional#empty()} when the publication date is
 *       unknown — never {@code null}.
 *   <li>{@code retries >= 0}.
 * </ul>
 */
class ProvenanceTest {

    private static final Instant EXTRACTED_AT = Instant.parse("2026-05-07T05:30:00Z");
    private static final Instant SOURCE_PUBLISHED_AT = Instant.parse("2026-01-15T00:00:00Z");

    @Nested
    @DisplayName("public API shape")
    class PublicApiShape {

        @Test
        @DisplayName("record component count stays within the canonical agent limit")
        void recordComponentCount() {
            assertThat(Provenance.class.getRecordComponents()).hasSizeLessThanOrEqualTo(5);
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a fully-populated bi-temporal provenance")
        void bitemporalProvenance() {
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.of(SOURCE_PUBLISHED_AT),
                    Optional.empty(),
                    Optional.empty(),
                    0);

            assertThat(p.model()).isEqualTo("claude-sonnet-4-7");
            assertThat(p.modelVersion()).isEqualTo("20260301");
            assertThat(p.extractedAt()).isEqualTo(EXTRACTED_AT);
            assertThat(p.sourcePublishedAt()).contains(SOURCE_PUBLISHED_AT);
            assertThat(p.retries()).isZero();
        }

        @Test
        @DisplayName("accepts Optional.empty() for unknown source publication date")
        void unknownSourcePublishedAt() {
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    2);

            assertThat(p.sourcePublishedAt()).isEmpty();
            assertThat(p.retries()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts retries == 0 (first-attempt success)")
        void retriesZero() {
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0);

            assertThat(p.retries()).isZero();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null model with NullPointerException")
        void nullModel() {
            assertThatThrownBy(() -> new Provenance(
                            null, "20260301", EXTRACTED_AT, Optional.empty(), Optional.empty(), Optional.empty(), 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("rejects empty model with IllegalArgumentException")
        void emptyModel() {
            assertThatThrownBy(() -> new Provenance(
                            "", "20260301", EXTRACTED_AT, Optional.empty(), Optional.empty(), Optional.empty(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("rejects whitespace-only model with IllegalArgumentException")
        void blankModel() {
            assertThatThrownBy(() -> new Provenance(
                            "   ", "20260301", EXTRACTED_AT, Optional.empty(), Optional.empty(), Optional.empty(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("rejects null modelVersion with NullPointerException")
        void nullModelVersion() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7",
                            null,
                            EXTRACTED_AT,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("modelVersion");
        }

        @Test
        @DisplayName("rejects empty modelVersion with IllegalArgumentException")
        void emptyModelVersion() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7",
                            "",
                            EXTRACTED_AT,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelVersion");
        }

        @Test
        @DisplayName("rejects whitespace-only modelVersion with IllegalArgumentException")
        void blankModelVersion() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7",
                            "   ",
                            EXTRACTED_AT,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelVersion");
        }

        @Test
        @DisplayName("rejects null extractedAt with NullPointerException")
        void nullExtractedAt() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7",
                            "20260301",
                            null,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("extractedAt");
        }

        @Test
        @DisplayName("rejects null sourcePublishedAt Optional with NullPointerException (must use Optional.empty())")
        void nullSourcePublishedAtOptional() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7", "20260301", EXTRACTED_AT, null, Optional.empty(), Optional.empty(), 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourcePublishedAt");
        }

        @Test
        @DisplayName("rejects negative retries with IllegalArgumentException")
        void negativeRetries() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7",
                            "20260301",
                            EXTRACTED_AT,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retries");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two provenances with equal fields (including bi-temporal) are equal")
        void recordEquality() {
            var a = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.of(SOURCE_PUBLISHED_AT),
                    Optional.empty(),
                    Optional.empty(),
                    1);
            var b = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.of(SOURCE_PUBLISHED_AT),
                    Optional.empty(),
                    Optional.empty(),
                    1);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Optional.empty() sourcePublishedAt is NOT equal to Optional.of(instant)")
        void emptyVsPresentSourcePublishedAt() {
            var withDate = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.of(SOURCE_PUBLISHED_AT),
                    Optional.empty(),
                    Optional.empty(),
                    0);
            var withoutDate = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0);

            assertThat(withDate).isNotEqualTo(withoutDate);
        }

        @Test
        @DisplayName("differing sourcePublishedAt instants are NOT equal (bi-temporal preserved)")
        void differentSourcePublishedAt() {
            var earlier = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.of(Instant.parse("2026-01-15T00:00:00Z")),
                    Optional.empty(),
                    Optional.empty(),
                    0);
            var later = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.of(Instant.parse("2026-02-15T00:00:00Z")),
                    Optional.empty(),
                    Optional.empty(),
                    0);

            assertThat(earlier).isNotEqualTo(later);
        }
    }

    @Nested
    @DisplayName("bi-temporal 3D (region + retainUntil)")
    class Bitemporal3D {

        @Test
        @DisplayName("rejects null region Optional with NullPointerException (must use Optional.empty())")
        void nullRegionOptional() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7", "20260301", EXTRACTED_AT, Optional.empty(), null, Optional.empty(), 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("region");
        }

        @Test
        @DisplayName("accepts Optional.empty() region (the OSS default — no residency claim)")
        void emptyRegionAccepted() {
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0);

            assertThat(p.region()).isEmpty();
        }

        @Test
        @DisplayName("Optional.of(\"ap-southeast-2\") round-trips through region()")
        void regionRoundTrips() {
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.of("ap-southeast-2"),
                    Optional.empty(),
                    0);

            assertThat(p.region()).contains("ap-southeast-2");
        }

        @Test
        @DisplayName("rejects null retainUntil Optional with NullPointerException (must use Optional.empty())")
        void nullRetainUntilOptional() {
            assertThatThrownBy(() -> new Provenance(
                            "claude-sonnet-4-7", "20260301", EXTRACTED_AT, Optional.empty(), Optional.empty(), null, 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("retainUntil");
        }

        @Test
        @DisplayName("accepts Optional.empty() retainUntil (the OSS default — no retention horizon)")
        void emptyRetainUntilAccepted() {
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0);

            assertThat(p.retainUntil()).isEmpty();
        }

        @Test
        @DisplayName("Optional.of(Instant) retainUntil round-trips through retainUntil()")
        void retainUntilRoundTrips() {
            var horizon = Instant.parse("2033-05-07T00:00:00Z");
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(horizon),
                    0);

            assertThat(p.retainUntil()).contains(horizon);
        }

        @Test
        @DisplayName("two provenances with different regions are NOT equal")
        void differentRegionsNotEqual() {
            var au = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.of("ap-southeast-2"),
                    Optional.empty(),
                    0);
            var us = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.of("us-east-1"),
                    Optional.empty(),
                    0);

            assertThat(au).isNotEqualTo(us);
        }

        @Test
        @DisplayName("two provenances with the same region and same retainUntil ARE equal")
        void sameRegionAndRetainUntilEqual() {
            var horizon = Instant.parse("2033-05-07T00:00:00Z");
            var a = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.of("ap-southeast-2"),
                    Optional.of(horizon),
                    0);
            var b = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.of("ap-southeast-2"),
                    Optional.of(horizon),
                    0);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("toString contains both region and retainUntil values when present (default record toString)")
        void toStringContainsBothFields() {
            var horizon = Instant.parse("2033-05-07T00:00:00Z");
            var p = new Provenance(
                    "claude-sonnet-4-7",
                    "20260301",
                    EXTRACTED_AT,
                    Optional.empty(),
                    Optional.of("ap-southeast-2"),
                    Optional.of(horizon),
                    0);

            assertThat(p.toString())
                    .contains("region=")
                    .contains("ap-southeast-2")
                    .contains("retainUntil=")
                    .contains("2033-05-07T00:00:00Z");
        }
    }
}
