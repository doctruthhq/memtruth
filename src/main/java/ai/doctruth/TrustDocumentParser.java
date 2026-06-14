package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import ai.doctruth.internal.runtime.DocTruthRuntime;

/**
 * Developer-facing v1 parser entrypoint for evidence-native {@link TrustDocument}s.
 *
 * <p>The implementation requires a configured local Rust runtime. Java/PDFBox
 * remains available through explicit compatibility fallback paths, but it is not
 * the default parser core.
 *
 * @since 1.0.0
 */
public final class TrustDocumentParser {

    private TrustDocumentParser() {
        throw new AssertionError("no instances");
    }

    public static TrustDocument parse(Path path) throws ParseException {
        return parse(path, ParserPreset.LITE);
    }

    public static TrustDocument parse(Path path, ParserPreset preset) throws ParseException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(preset, "preset");
        return parseWithRequiredRuntime(path, sha256SourceFile(path), preset).withEvaluatedAuditGrade();
    }

    public static TrustDocument parse(byte[] bytes, String sourceFilename) throws ParseException {
        Objects.requireNonNull(bytes, "bytes");
        requireSourceFilename(sourceFilename);
        return parseBytes(bytes.clone(), sourceFilename, ParserPreset.LITE);
    }

    public static TrustDocument parse(byte[] bytes, String sourceFilename, ParserPreset preset) throws ParseException {
        Objects.requireNonNull(bytes, "bytes");
        requireSourceFilename(sourceFilename);
        Objects.requireNonNull(preset, "preset");
        return parseBytes(bytes.clone(), sourceFilename, preset);
    }

    public static TrustDocument parse(InputStream input, String sourceFilename) throws ParseException {
        return parse(input, sourceFilename, ParserPreset.LITE);
    }

    public static TrustDocument parse(InputStream input, String sourceFilename, ParserPreset preset) throws ParseException {
        Objects.requireNonNull(input, "input");
        requireSourceFilename(sourceFilename);
        Objects.requireNonNull(preset, "preset");
        Path temp = null;
        try {
            temp = Files.createTempFile("doctruth-", ".pdf");
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            return parseTempFile(temp, sourceFilename, preset);
        } catch (IOException e) {
            throw new ParseException(
                    "PDF_STREAM_READ_FAILED",
                    "failed to read parser input stream: " + e.getMessage(),
                    sourceFilename,
                    java.util.OptionalInt.empty(),
                    e);
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    public static List<TrustDocument> parseBatch(List<Path> paths) throws ParseException {
        return parseBatch(paths, ParserPreset.LITE);
    }

    public static List<TrustDocument> parseBatch(List<Path> paths, ParserPreset preset) throws ParseException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(preset, "preset");
        var out = new java.util.ArrayList<TrustDocument>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            out.add(parse(Objects.requireNonNull(paths.get(i), "paths[" + i + "]"), preset));
        }
        return List.copyOf(out);
    }

    private static TrustDocument parseBytes(byte[] bytes, String sourceFilename, ParserPreset preset)
            throws ParseException {
        Path temp = null;
        try {
            temp = Files.createTempFile("doctruth-", ".pdf");
            Files.write(temp, bytes);
            return parseTempFile(temp, sourceFilename, preset);
        } catch (IOException e) {
            throw new ParseException(
                    "PDF_BYTES_PARSE_FAILED",
                    "failed to parse PDF bytes: " + e.getMessage(),
                    sourceFilename,
                    java.util.OptionalInt.empty(),
                    e);
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    private static TrustDocument parseTempFile(Path temp, String sourceFilename, ParserPreset preset)
            throws ParseException {
        return renameSource(parseWithRequiredRuntime(temp, sha256SourceFile(temp), preset), sourceFilename)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument parseWithRequiredRuntime(Path path, String sourceHash, ParserPreset preset)
            throws ParseException {
        var request = new ParserRequest(
                path,
                sourceHash,
                preset.parserRun("sidecar"),
                preset.runtimePolicy().offlineMode(),
                preset.runtimePolicy().allowModelDownloads());
        return new SidecarParserBackend(DocTruthRuntime.requireConfiguredCommand(path)).parse(request);
    }

    private static TrustDocument renameSource(TrustDocument document, String sourceFilename) {
        var metadata = new DocumentMetadata(
                sourceFilename,
                document.source().metadata().pageCount(),
                document.source().metadata().sourcePublishedAt());
        var source = new TrustDocumentSource(sourceFilename, document.source().sourceHash(), metadata);
        return new TrustDocument(document.docId(), source, document.body(), document.parserRun(), document.auditGradeStatus());
    }

    private static void requireSourceFilename(String sourceFilename) {
        Objects.requireNonNull(sourceFilename, "sourceFilename");
        if (sourceFilename.isBlank()) {
            throw new IllegalArgumentException("sourceFilename must not be blank");
        }
    }

    static String sha256SourceFile(Path path) throws ParseException {
        try {
            return "sha256:" + sha256Hex(Files.newInputStream(path));
        } catch (IOException e) {
            throw new ParseException(
                    "SOURCE_HASH_FAILED",
                    "failed to hash source document: " + e.getMessage(),
                    path.toString(),
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary parser files are best-effort cleanup only.
        }
    }
}
