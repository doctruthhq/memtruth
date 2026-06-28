package ai.doctruth;

import java.util.List;

/**
 * Legacy Java/PDFBox oracle backend behind the v1 parser SPI.
 *
 * <p>This backend is not the default parser core. It exists for migration,
 * debugging, and differential tests against the Rust runtime.
 *
 * @since 1.0.0
 */
public final class PdfBoxParserBackend implements ParserBackend {

    private static final String BACKEND = "pdfbox";

    @Override
    public TrustDocument parse(ParserRequest request) throws ParseException {
        var parsed = PdfDocumentParser.parse(request.sourcePath());
        return withRenderedPages(TrustDocument.fromParsed(parsed, request.sourceHash(), request.parserRun()), request);
    }

    private static TrustDocument withRenderedPages(TrustDocument document, ParserRequest request)
            throws ParseException {
        var body = new TrustDocumentBody(
                PdfPageImages.renderedPages(request.sourcePath()),
                document.body().units(),
                document.body().tables());
        return new TrustDocument(
                document.docId(), document.source(), body, document.parserRun(), document.auditGradeStatus());
    }

    @Override
    public ParserCapabilities capabilities() {
        return new ParserCapabilities(
                BACKEND,
                true,
                false,
                false,
                List.of("json_full", "json_evidence", "markdown_clean", "plain_text", "compact_llm"));
    }

    @Override
    public ParserHealth doctor() {
        return new ParserHealth(BACKEND, true, List.of());
    }
}
