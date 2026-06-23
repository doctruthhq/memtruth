package ai.doctruth.opendataloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import ai.doctruth.ParseException;
import ai.doctruth.ParserRun;
import ai.doctruth.PdfDocumentParser;
import ai.doctruth.TrustDocument;

/** First-class local Java parser backend for OpenDataLoader-compatible quality work. */
public final class OpenDataLoaderJavaBackend {

    public static final String BACKEND = "opendataloader-java-core";
    public static final String SCHEMA_VERSION = "doctruth.opendataloader.backend.v1";

    public OpenDataLoaderBackendResponse parse(OpenDataLoaderBackendRequest request) throws ParseException {
        long started = System.nanoTime();
        var parsed = PdfDocumentParser.parse(request.document());
        long elapsedMs = elapsedMs(started);
        var parserRun = new ParserRun(
                "parser-run-opendataloader-java-core",
                "1.0.0",
                request.preset().id(),
                BACKEND,
                List.of(),
                List.of(),
                Map.of("name", BACKEND),
                elapsedMs);
        var trustDocument = TrustDocument.fromParsed(parsed, sha256SourceFile(request), parserRun)
                .withEvaluatedAuditGrade();
        return responseFrom(trustDocument, elapsedMs);
    }

    private static OpenDataLoaderBackendResponse responseFrom(TrustDocument trustDocument, long elapsedMs) {
        var blocks = OpenDataLoaderTrustDocumentAdapter.blocks(trustDocument);
        return OpenDataLoaderBackendResponse.fromParts(
                BACKEND,
                SCHEMA_VERSION,
                trustDocument.toMarkdownClean(),
                blocks,
                OpenDataLoaderTrustDocumentAdapter.tables(trustDocument),
                OpenDataLoaderTrustDocumentAdapter.headings(trustDocument),
                OpenDataLoaderTrustDocumentAdapter.sourceMap(trustDocument),
                trustDocument.parserRun().warnings(),
                Map.of("elapsedMs", elapsedMs),
                trustDocument);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String sha256SourceFile(OpenDataLoaderBackendRequest request) throws ParseException {
        try {
            return "sha256:" + sha256Hex(Files.newInputStream(request.document()));
        } catch (IOException e) {
            throw new ParseException(
                    "SOURCE_HASH_FAILED",
                    "failed to hash source document: " + e.getMessage(),
                    request.document().toString(),
                    java.util.OptionalInt.empty(),
                    e);
        }
    }

    private static String sha256Hex(InputStream input) throws IOException {
        try (input) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }
}
