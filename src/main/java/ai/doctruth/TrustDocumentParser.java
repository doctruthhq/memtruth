package ai.doctruth;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Public SDK entry point for parsing PDFs into DocTruth's audit-ready TrustDocument format.
 *
 * @since 0.2.0
 */
public final class TrustDocumentParser {

    private TrustDocumentParser() {
        throw new AssertionError("no instances");
    }

    public static TrustDocument parse(Path pdfPath) throws ParseException {
        return parse(pdfPath, PdfParserBackend.OPENDATALOADER);
    }

    public static TrustDocument parse(Path pdfPath, PdfParserBackend backend) throws ParseException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        Objects.requireNonNull(backend, "backend");
        return TrustDocument.fromParsed(PdfDocumentParser.parse(pdfPath, backend), pdfPath, backend);
    }
}
