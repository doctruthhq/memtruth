package ai.doctruth.spi;

import java.util.Objects;

/**
 * Sign / wrap an audit JSON document for tamper-evident persistence. Default
 * {@link #IDENTITY identity} — returns the JSON unchanged. Commercial-tier impls:
 * {@code HmacSignatureProvider}, {@code Ed25519SignatureProvider}.
 *
 * <p>Per AGENTS.md "Engineering principles" §2 — when this is invoked, callers know the
 * sealed JSON they get back is what hits long-term storage; tamper-evidence is the
 * differentiator we sell.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface SignatureProvider {
    /**
     * Sign or wrap {@code auditJson} and return the result. Implementations must be pure
     * (same input → same output) and must NOT throw on any non-null input.
     */
    String sign(String auditJson);

    /** Identity — returns the JSON unchanged. */
    SignatureProvider IDENTITY = json -> Objects.requireNonNull(json, "auditJson");
}
