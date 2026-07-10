/**
 * Internal section-rendering helpers shared by Layer 2 (extraction) and Layer 3 (context
 * strategies). NOT public API.
 *
 * <p>The single {@link ai.doctruth.internal.render.SectionRenderer} entry point is the only
 * place in the codebase that converts a {@link ai.doctruth.ParsedSection} to a flat string,
 * so changes to that representation happen in exactly one location (per CONTRIBUTING.md
 * "Engineering principles" §1 — decoupled by default, single source of truth).
 */
package ai.doctruth.internal.render;
