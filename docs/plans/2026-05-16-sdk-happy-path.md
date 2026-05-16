# SDK Happy Path Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the primary SDK path read like `DocTruth.withProvider(provider).fromPdf(path).extract(...).withEvidence().run()`.

**Architecture:** Keep the existing low-level API intact. Add a thin convenience layer that owns the provider and parsed document, delegates to `ExtractionBuilder`, and adds result helper aliases for common citation and audit operations.

**Tech Stack:** Java 25, existing parser classes, existing provider classes, JUnit 5, AssertJ.

---

### Task 1: Result Helpers

**Files:**
- Modify: `src/main/java/ai/doctruth/ExtractionResult.java`
- Test: `src/test/java/ai/doctruth/ExtractionResultAuditJsonTest.java`

**Steps:**
1. Write failing tests for `citation(String)` and `writeAudit(Path)`.
2. Implement citation convenience methods (`findCitation`, `requireCitation`, legacy `citation`) and `writeAudit(path)`.
3. Run the focused result tests.

### Task 2: Document-First SDK Flow

**Files:**
- Modify: `src/main/java/ai/doctruth/DocTruth.java`
- Create: `src/main/java/ai/doctruth/DocTruthClient.java`
- Create: `src/main/java/ai/doctruth/DocTruthDocument.java`
- Create: `src/main/java/ai/doctruth/DocumentExtractionBuilder.java`
- Test: `src/test/java/ai/doctruth/DocTruthHappyPathTest.java`

**Steps:**
1. Write failing tests for `withProvider(provider).from(parsedDoc).extract(...).withEvidence().run()`.
2. Write failing tests for `fromPdf(Path)` using a generated one-page PDF.
3. Implement the thin wrappers and delegate to existing parsers/builders.
4. Run the focused happy-path tests.

### Task 3: OpenAI Convenience

**Files:**
- Modify: `src/main/java/ai/doctruth/DocTruth.java`
- Test: `src/test/java/ai/doctruth/DocTruthHappyPathTest.java`

**Steps:**
1. Write failing tests for `withOpenAi(String apiKey)` and blank-key handling.
2. Implement the static convenience factory.
3. Keep `withOpenAi()` env-based but document that it reads `OPENAI_API_KEY`.

### Task 4: Docs

**Files:**
- Modify: `README.md`
- Modify as needed: `examples/quickstart/README.md`

**Steps:**
1. Move the new SDK happy path into the first Java example.
2. Keep CLI positioned as try/debug/inspect.
3. Run formatting and verification.
