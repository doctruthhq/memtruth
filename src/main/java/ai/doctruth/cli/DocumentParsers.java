package ai.doctruth.cli;

import java.nio.file.Path;
import java.util.Locale;

import ai.doctruth.CsvDocumentParser;
import ai.doctruth.DocxDocumentParser;
import ai.doctruth.ParseException;
import ai.doctruth.ParsedDocument;
import ai.doctruth.PdfDocumentParser;
import ai.doctruth.XlsxDocumentParser;
import ai.doctruth.spi.OcrEngines;

final class DocumentParsers {

    private DocumentParsers() {
        throw new AssertionError("no instances");
    }

    static ParsedDocument parse(Path path) throws CliException {
        try {
            return switch (extension(path)) {
                case "pdf" -> PdfDocumentParser.parse(path, OcrEngines.defaultLocal());
                case "docx" -> DocxDocumentParser.parse(path);
                case "xlsx" -> XlsxDocumentParser.parse(path);
                case "csv" -> CsvDocumentParser.parse(path);
                default -> throw new CliException("unsupported document format: " + path);
            };
        } catch (ParseException e) {
            throw new CliException("failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
