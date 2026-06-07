package ai.doctruth;

import java.nio.file.Path;
import java.util.Objects;

import ai.doctruth.spi.OcrEngine;

/**
 * Document-first SDK entry point. Use this layer when the caller wants a short
 * "document to value plus evidence" flow; use {@link DocTruth#from(LlmProvider)} for
 * lower-level orchestration.
 *
 * @since 0.2.0
 */
public final class DocTruthClient {

    private final LlmProvider provider;

    DocTruthClient(LlmProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public DocTruthDocument from(ParsedDocument document) {
        return new DocTruthDocument(provider, document);
    }

    public DocTruthDocument fromPdf(Path path) throws ParseException {
        return from(PdfDocumentParser.parse(path));
    }

    public DocTruthDocument fromPdf(Path path, OcrEngine ocrEngine) throws ParseException {
        return from(PdfDocumentParser.parse(path, ocrEngine));
    }

    public DocTruthDocument fromPdf(String path) throws ParseException {
        Objects.requireNonNull(path, "path");
        return fromPdf(Path.of(path));
    }

    public DocTruthDocument fromPdf(String path, OcrEngine ocrEngine) throws ParseException {
        Objects.requireNonNull(path, "path");
        return fromPdf(Path.of(path), ocrEngine);
    }

    public DocTruthDocument fromDocx(Path path) throws ParseException {
        return from(DocxDocumentParser.parse(path));
    }

    public DocTruthDocument fromCsv(Path path) throws ParseException {
        return from(CsvDocumentParser.parse(path));
    }

    public DocTruthDocument fromXlsx(Path path) throws ParseException {
        return from(XlsxDocumentParser.parse(path));
    }
}
