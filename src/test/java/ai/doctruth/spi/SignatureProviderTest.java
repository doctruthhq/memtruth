package ai.doctruth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link SignatureProvider}.
 *
 * <p>The OSS default is {@link SignatureProvider#IDENTITY} — it returns the input
 * unchanged but rejects null. Commercial-tier impls (HMAC, Ed25519) plug in via
 * the same single-method interface.
 */
class SignatureProviderTest {

    @Nested
    @DisplayName("IDENTITY")
    class Identity {

        @Test
        @DisplayName("IDENTITY.sign(\"{}\") returns \"{}\" unchanged")
        void returnsInputUnchanged() {
            assertThat(SignatureProvider.IDENTITY.sign("{}")).isEqualTo("{}");
        }

        @Test
        @DisplayName("IDENTITY.sign(longJson) returns the same string instance")
        void returnsLongJsonUnchanged() {
            String json = "{\"@context\":\"https://www.w3.org/ns/prov\",\"foo\":42}";

            assertThat(SignatureProvider.IDENTITY.sign(json)).isEqualTo(json);
        }

        @Test
        @DisplayName("IDENTITY.sign(null) throws NullPointerException with 'auditJson' in the message")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SignatureProvider.IDENTITY.sign(null))
                    .withMessageContaining("auditJson");
        }
    }

    @Nested
    @DisplayName("custom impl")
    class Custom {

        @Test
        @DisplayName("a custom uppercasing impl wraps the input as expected (round-trip shape)")
        void uppercasingRoundTrip() {
            SignatureProvider upper = String::toUpperCase;

            assertThat(upper.sign("{\"foo\":42}")).isEqualTo("{\"FOO\":42}");
        }
    }
}
