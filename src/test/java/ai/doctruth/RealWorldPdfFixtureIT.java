package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-world fixture smoke test for {@link PdfDocumentParser}. Loads every {@code .pdf} in
 * {@code fixtures/pdf/} (gitignored — corpus stays local) and asserts the parser handles
 * real-world variability without bugs.
 *
 * <p>This is NOT a unit test — it requires the local fixture corpus. Skipped automatically
 * when {@code fixtures/pdf} is empty or missing. Override the fixture root via the
 * {@code DOCTRUTH_FIXTURES_DIR} env var (default: {@code fixtures}).
 *
 * <p>Pass rate gate: ≥ 95%. Below that means a real PDFBox edge case is breaking us and
 * we should investigate before tagging a release.
 *
 * <p>Companion to {@link EndToEndComplexPdfIT} — that one drives 4-provider WireMock
 * pipeline against synthetic PDFs; this one drives ONLY the parser layer against real ones.
 */
@DisplayName("real-world PDF fixture smoke test")
class RealWorldPdfFixtureIT {

    private static final Logger LOG = LoggerFactory.getLogger(RealWorldPdfFixtureIT.class);

    private static final Path FIXTURE_DIR = Path.of(System.getenv().getOrDefault("DOCTRUTH_FIXTURES_DIR", "fixtures"))
            .resolve("pdf");

    private static final double PASS_RATE_GATE = 0.95;
    private static final long LARGE_PDF_BYTES = 1_000_000L;
    private static final long LARGE_PDF_BUDGET_NANOS = 10L * 1_000_000_000L;
    private static final int LIKELY_SCANNED_THRESHOLD_CHARS = 50;

    @BeforeAll
    static void requireFixtures() throws IOException {
        assumeTrue(Files.isDirectory(FIXTURE_DIR), "fixtures/pdf not present — skipping real-world IT");
        try (var stream = Files.list(FIXTURE_DIR)) {
            assumeTrue(
                    stream.anyMatch(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")),
                    "fixtures/pdf has no .pdf files — skipping real-world IT");
        }
    }

    @Test
    @DisplayName("≥95% of real-world PDFs parse cleanly with no parser bugs")
    void parses95PercentOfRealWorldPdfs() throws IOException {
        var pdfs = listPdfs();
        var bugs = new ArrayList<String>();
        var errorCodes = new TreeMap<String, Integer>();
        var failureDetails = new java.util.ArrayList<String>();
        var pageCounts = new ArrayList<Integer>();
        var sectionCounts = new ArrayList<Integer>();
        var sizes = new ArrayList<Long>();
        int success = 0;
        int failure = 0;
        var totalNanos = new AtomicLong();

        for (Path pdf : pdfs) {
            long size = Files.size(pdf);
            sizes.add(size);
            long t0 = System.nanoTime();
            try {
                var doc = PdfDocumentParser.parse(pdf);
                totalNanos.addAndGet(System.nanoTime() - t0);
                assertThat(doc.docId()).startsWith("sha256:");
                assertThat(doc.metadata().pageCount()).isGreaterThanOrEqualTo(1);
                assertThat(doc.metadata().sourceFilename().toLowerCase(Locale.ROOT))
                        .endsWith(".pdf");
                pageCounts.add(doc.metadata().pageCount());
                sectionCounts.add(doc.sections().size());
                success++;
            } catch (ParseException pe) {
                totalNanos.addAndGet(System.nanoTime() - t0);
                errorCodes.merge(pe.errorCode(), 1, Integer::sum);
                Throwable cause = pe.getCause();
                String causeShape =
                        cause == null ? "(no cause)" : cause.getClass().getName() + ": " + cause.getMessage();
                failureDetails.add(pdf.getFileName() + " | " + pe.errorCode() + " | " + causeShape);
                failure++;
            } catch (Throwable bug) {
                totalNanos.addAndGet(System.nanoTime() - t0);
                bugs.add(pdf.getFileName() + " → " + bug.getClass().getSimpleName() + ": " + bug.getMessage());
            }
        }

        int total = pdfs.size();
        double passRate = total == 0 ? 0.0 : (double) success / total;
        long meanMicros = total == 0 ? 0L : totalNanos.get() / 1_000L / total;
        LOG.info(
                "real-world PDF corpus summary: total={} success={} failure={} bugs={} passRate={}",
                total,
                success,
                failure,
                bugs.size(),
                String.format(Locale.ROOT, "%.4f", passRate));
        LOG.info("errorCode distribution: {}", errorCodes);
        for (var detail : failureDetails) {
            LOG.warn("PDF parse failure: {}", detail);
        }
        LOG.info("pageCount min/median/max: {}", minMedianMax(pageCounts));
        LOG.info("sectionCount min/median/max: {}", minMedianMax(sectionCounts));
        LOG.info("size-bytes min/median/max: {}", minMedianMaxLong(sizes));
        LOG.info("total parse time = {} ms, mean = {} us", totalNanos.get() / 1_000_000L, meanMicros);

        assertThat(bugs)
                .as("non-ParseException failures from PdfDocumentParser indicate parser bugs: %s", bugs)
                .isEmpty();
        assertThat(passRate)
                .as("real-world pass rate %d/%d, errorCodes=%s", success, total, errorCodes)
                .isGreaterThanOrEqualTo(PASS_RATE_GATE);
    }

    @Test
    @DisplayName("docId is unique per content — colliding hashes only allowed when bytes are identical")
    void docIdsAreUniquePerContent() throws IOException {
        var pdfs = listPdfs();
        Map<String, Path> seen = new HashMap<>();
        var collisions = new ArrayList<String>();
        for (Path pdf : pdfs) {
            String docId = parseQuietly(pdf);
            if (docId == null) {
                continue;
            }
            Path prior = seen.putIfAbsent(docId, pdf);
            if (prior != null && Files.mismatch(prior, pdf) != -1L) {
                collisions.add(docId + " collision: " + prior.getFileName() + " vs " + pdf.getFileName());
            }
        }
        assertThat(collisions)
                .as("SHA-256 collisions on distinct content would indicate a hashing bug: %s", collisions)
                .isEmpty();
    }

    @Test
    @DisplayName("docId never leaks the source filename or parent dir name (APP 11.1 PII compliance)")
    void docIdNeverLeaksFilePath() throws IOException {
        var pdfs = listPdfs();
        var leaks = new ArrayList<String>();
        for (Path pdf : pdfs) {
            String docId = parseQuietly(pdf);
            if (docId == null) {
                continue;
            }
            String filename = pdf.getFileName().toString();
            String parent =
                    pdf.getParent() == null ? "" : pdf.getParent().getFileName().toString();
            String stem = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            if (docId.contains(filename) || docId.contains(stem) || (!parent.isEmpty() && docId.contains(parent))) {
                leaks.add(filename + " → " + docId);
            }
        }
        assertThat(leaks)
                .as("docId must not embed filesystem identifiers: %s", leaks)
                .isEmpty();
    }

    @Test
    @DisplayName("parser-reported pageCount matches PDFBox's PDDocument.getNumberOfPages()")
    void pageCountMatchesMetadataConsistently() throws IOException {
        var pdfs = listPdfs();
        var mismatches = new ArrayList<String>();
        for (Path pdf : pdfs) {
            ParsedDocument doc;
            try {
                doc = PdfDocumentParser.parse(pdf);
            } catch (ParseException ignored) {
                // ParseException paths are covered by the smoke test; here we only audit successful parses.
                continue;
            }
            try (PDDocument reopened = Loader.loadPDF(pdf.toFile())) {
                int actual = reopened.getNumberOfPages();
                if (doc.metadata().pageCount() != actual) {
                    mismatches.add(
                            pdf.getFileName() + " parser=" + doc.metadata().pageCount() + " pdfbox=" + actual);
                }
            } catch (IOException reopenFailure) {
                // If PDFBox itself can't reopen, the parser shouldn't have succeeded — investigate.
                mismatches.add(pdf.getFileName() + " reopen-failed: " + reopenFailure.getMessage());
            }
        }
        assertThat(mismatches)
                .as("parser/PDFBox pageCount disagreement: %s", mismatches)
                .isEmpty();
    }

    @Test
    @DisplayName("PDFs > 1MB still parse within a 10-second wall-clock budget")
    void largePdfsParseWithinReasonableTime() throws IOException {
        var pdfs = listPdfs();
        var slow = new ArrayList<String>();
        int largeCount = 0;
        for (Path pdf : pdfs) {
            if (Files.size(pdf) <= LARGE_PDF_BYTES) {
                continue;
            }
            largeCount++;
            long t0 = System.nanoTime();
            try {
                PdfDocumentParser.parse(pdf);
            } catch (ParseException ignored) {
                // failures here are not slowness — covered by the smoke test
            }
            long elapsedNanos = System.nanoTime() - t0;
            if (elapsedNanos > LARGE_PDF_BUDGET_NANOS) {
                slow.add(pdf.getFileName() + " took " + (elapsedNanos / 1_000_000L) + "ms");
            }
        }
        LOG.info("large PDFs (>{}B): {}", LARGE_PDF_BYTES, largeCount);
        assertThat(slow)
                .as("PDFs exceeding %d-second budget: %s", LARGE_PDF_BUDGET_NANOS / 1_000_000_000L, slow)
                .isEmpty();
    }

    @Test
    @DisplayName("PDFs that yield <50 chars of text are flagged as likely-scanned (not a failure)")
    void scannedOrEmptyTextPdfsAreFlagged() throws IOException {
        var pdfs = listPdfs();
        var likelyScanned = new ArrayList<String>();
        for (Path pdf : pdfs) {
            ParsedDocument doc;
            try {
                doc = PdfDocumentParser.parse(pdf);
            } catch (ParseException ignored) {
                continue;
            }
            int totalChars = 0;
            for (ParsedSection section : doc.sections()) {
                if (section instanceof TextSection ts) {
                    totalChars += ts.text().length();
                }
            }
            if (totalChars < LIKELY_SCANNED_THRESHOLD_CHARS) {
                LOG.warn("likely scanned/image-only PDF: {} (extracted {} chars)", pdf.getFileName(), totalChars);
                likelyScanned.add(pdf.getFileName().toString());
            }
        }
        LOG.info(
                "likely-scanned-or-image-only count={} top10={}",
                likelyScanned.size(),
                likelyScanned.stream().limit(10).toList());
        // Intentionally non-failing: scanned PDFs are a routing decision for the calling app.
        assertThat(likelyScanned).isNotNull();
    }

    @Test
    @DisplayName("every emitted TextSection carries a non-null BlockKind, and HEADING leads ≥30% of pages")
    void emittedSectionsHaveKindClassification() throws IOException {
        var pdfs = listPdfs();
        var nullKindOffenders = new ArrayList<String>();
        var kindCounts = new EnumMap<BlockKind, Integer>(BlockKind.class);
        for (var k : BlockKind.values()) {
            kindCounts.put(k, 0);
        }
        int pagesWithFirstHeading = 0;
        int pagesInspected = 0;
        for (Path pdf : pdfs) {
            ParsedDocument doc;
            try {
                doc = PdfDocumentParser.parse(pdf);
            } catch (ParseException ignored) {
                continue;
            }
            int seenPage = -1;
            for (var section : doc.sections()) {
                if (section instanceof TextSection ts) {
                    if (ts.kind() == null) {
                        nullKindOffenders.add(pdf.getFileName().toString());
                    } else {
                        kindCounts.merge(ts.kind(), 1, Integer::sum);
                    }
                    int pageStart = ts.location().pageStart();
                    if (pageStart != seenPage) {
                        seenPage = pageStart;
                        pagesInspected++;
                        if (ts.kind() == BlockKind.HEADING) {
                            pagesWithFirstHeading++;
                        }
                    }
                }
            }
        }
        double headingFirstRatio = pagesInspected == 0 ? 0.0 : (double) pagesWithFirstHeading / pagesInspected;
        LOG.info(
                "kinds: HEADING={} BODY={} LIST={} OTHER={}",
                kindCounts.get(BlockKind.HEADING),
                kindCounts.get(BlockKind.BODY),
                kindCounts.get(BlockKind.LIST),
                kindCounts.get(BlockKind.OTHER));
        LOG.info(
                "pages inspected={} pages opening with HEADING={} ratio={}",
                pagesInspected,
                pagesWithFirstHeading,
                String.format(Locale.ROOT, "%.4f", headingFirstRatio));
        assertThat(nullKindOffenders)
                .as("BlockKind must never be null on emitted sections: %s", nullKindOffenders)
                .isEmpty();
        assertThat(headingFirstRatio)
                .as(
                        "≥30%% of pages should open with a HEADING block — most CVs / contracts start with one. "
                                + "headingFirst=%d / pages=%d",
                        pagesWithFirstHeading, pagesInspected)
                .isGreaterThanOrEqualTo(0.30);
    }

    // --- helpers ---------------------------------------------------------

    private static List<Path> listPdfs() throws IOException {
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            return stream.filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
    }

    /** Returns the docId on success, {@code null} on {@link ParseException} (silently skipped). */
    private static String parseQuietly(Path pdf) {
        try {
            return PdfDocumentParser.parse(pdf).docId();
        } catch (ParseException ignored) {
            return null;
        }
    }

    private static String minMedianMax(List<Integer> values) {
        if (values.isEmpty()) {
            return "n/a";
        }
        var sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int min = sorted.get(0);
        int max = sorted.get(sorted.size() - 1);
        int median = sorted.get(sorted.size() / 2);
        return min + "/" + median + "/" + max;
    }

    private static String minMedianMaxLong(List<Long> values) {
        if (values.isEmpty()) {
            return "n/a";
        }
        var sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        long median = sorted.get(sorted.size() / 2);
        return min + "/" + median + "/" + max;
    }
}
