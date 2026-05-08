// SPDX-License-Identifier: Apache-2.0
//
// doctruth-java quickstart — copy-paste runnable demo.
//
// Drop this file into any Java 25+ project that has doctruth-java on the
// classpath, set ANTHROPIC_API_KEY, and run `main`. See README.md next to
// this file for the exact shell commands.
package ai.doctruth.examples.quickstart;

import ai.doctruth.AnthropicProvider;
import ai.doctruth.DocTruth;
import ai.doctruth.ParsedDocument;
import ai.doctruth.PdfDocumentParser;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class Quickstart {

    // The shape we want extracted. Records map 1:1 to the JSON the LLM returns.
    record Contract(String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue) {}

    public static void main(String[] args) throws Exception {
        // 1. API key from env. Fail fast with a clear message — no silent fallback.
        var apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: set ANTHROPIC_API_KEY in your environment.");
            System.err.println("       e.g. export ANTHROPIC_API_KEY=sk-ant-...");
            System.exit(2);
        }

        // 2. Source PDF: caller-supplied path, or a tiny generated one so the
        //    demo runs with zero filesystem setup.
        var pdfPath = (args.length > 0)
                ? Path.of(args[0])
                : writeSamplePdf();
        System.out.println("Source PDF: " + pdfPath);

        // 3. Parse PDF -> ParsedDocument (layout blocks with page+line preserved).
        ParsedDocument doc = PdfDocumentParser.parse(pdfPath);
        System.out.println("Parsed " + doc.metadata().pageCount() + " page(s) from " + doc.metadata().sourceFilename());

        // 4. The fluent extraction call — provider, prompt, target type, evidence flags.
        //    .withProvenance() asks the library to attach a Citation per extracted field.
        //    .withBitemporal() records both extractedAt + sourcePublishedAt on the result.
        var result = DocTruth.from(new AnthropicProvider(apiKey))
                .extract("Extract the contract terms", Contract.class)
                .withProvenance()
                .withSourcePublishedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .withBitemporal()
                .withConfidence()
                .run(doc);

        // 5. Show the extracted value and the audit trail that makes it defensible.
        System.out.println();
        System.out.println("Extracted value:");
        System.out.println("  " + result.value());

        System.out.println();
        System.out.println("Citations: " + result.citations().size() + " field(s)");
        result.citations().entrySet().stream().findFirst().ifPresent(e -> {
            var c = e.getValue();
            System.out.printf(
                    "  first: %s -> page %d line %d  matchScore=%.2f%n",
                    e.getKey(), c.location().pageStart(), c.location().lineStart(), c.matchScore());
        });

        System.out.println();
        System.out.println("Confidence: " + result.confidence().size() + " field(s)");

        var p = result.provenance();
        System.out.println();
        System.out.println("Provenance:");
        System.out.println("  model=" + p.model() + " modelVersion=" + p.modelVersion());
        System.out.println("  extractedAt=" + p.extractedAt());
        p.sourcePublishedAt().ifPresent(t -> System.out.println("  sourcePublishedAt=" + t));

        // 6. JSON-LD audit log — what compliance teams ingest. Written next to the PDF.
        var auditPath = pdfPath.resolveSibling("audit.json");
        result.toAuditJson(auditPath);
        System.out.println();
        System.out.println("Audit JSON written to: " + auditPath);
    }

    /** Build a 1-page PDF in a temp file so the demo runs with no inputs. */
    private static Path writeSamplePdf() throws Exception {
        var path = Files.createTempFile("doctruth-quickstart-", ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 720);
                String[] lines = {
                    "CONTRACT 2026-001",
                    "Party A: Acme Industrial Materials Pty Ltd",
                    "Party B: BetaCorp Construction Ltd",
                    "Effective Date: 2026-04-01",
                    "Total Value: AUD 2,450,000",
                };
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) cs.newLineAtOffset(0, -18);
                    cs.showText(lines[i]);
                }
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }
}
