/**
 * Service Provider Interface (SPI) extension points for {@code doctruth-java}. The OSS
 * library publishes these interfaces and ships default implementations (NOOP / IDENTITY).
 * Commercial-tier consumers (the {@code doctruth-enterprise} jar) plug in richer
 * implementations — HMAC-signed audit JSON, SIEM push listeners, region-enforcing
 * transports — without changing the OSS API. See ADR 0006.
 *
 * @since 0.1.0
 */
package ai.doctruth.spi;
