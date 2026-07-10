/**
 * Failsafe-backed retry/backoff helper used by every provider. NOT public API.
 *
 * <p>Anything under this package may be renamed, moved, or removed without a major
 * version bump. Failsafe ({@code dev.failsafe.*}) types are confined to this package by
 * design (per CONTRIBUTING.md §1 decoupling); they MUST NOT leak through public method
 * signatures.
 */
package ai.doctruth.internal.retry;
