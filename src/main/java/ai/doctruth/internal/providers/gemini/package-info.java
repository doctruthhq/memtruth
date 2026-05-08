/**
 * Hand-rolled Google Gemini {@code generateContent} client per ADR 0003 (no vendor SDK on
 * the classpath). NOT public API.
 *
 * <p>Anything under this package may be renamed, moved, or removed without a major version
 * bump. Wire-shape records live under {@code .wire.*}; the HTTP delegate lives here.
 *
 * @hidden
 */
package ai.doctruth.internal.providers.gemini;
