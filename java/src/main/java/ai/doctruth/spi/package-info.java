/**
 * Service Provider Interface (SPI) extension points for DocTruth. The library publishes
 * these interfaces and ships conservative default implementations (NOOP / IDENTITY).
 * Callers can plug in organization-specific implementations — signed audit JSON, event
 * listeners, region-aware transports — without changing the public API.
 *
 * @since 0.1.0
 */
package ai.doctruth.spi;
