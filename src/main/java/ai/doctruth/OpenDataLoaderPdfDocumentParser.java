package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OpenDataLoaderPdfDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(OpenDataLoaderPdfDocumentParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object OPENDATALOADER_LOCK = new Object();
    private static OpenDataLoaderRunner openDataLoaderRunner =
            (pdfPath, outputDir, config) -> OpenDataLoaderPDF.processFile(pdfPath.toString(), config);

    private OpenDataLoaderPdfDocumentParser() {
        throw new AssertionError("no instances");
    }

    static ParsedDocument parse(Path pdfPath) throws ParseException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        requireRegularFile(pdfPath);
        Path outputDir = null;
        try {
            outputDir = Files.createTempDirectory("doctruth-opendataloader-");
            var geometry = OpenDataLoaderPdfGeometry.lazy(pdfPath);
            Config config = config(outputDir);
            synchronized (OPENDATALOADER_LOCK) {
                try {
                    openDataLoaderRunner.process(pdfPath, outputDir, config);
                } finally {
                    OpenDataLoaderPDF.shutdown();
                }
            }
            Path jsonPath = outputDir.resolve(jsonFilename(pdfPath));
            if (!Files.isRegularFile(jsonPath)) {
                throw new IOException("OpenDataLoader did not produce JSON output: " + jsonPath);
            }
            var parsed = readOpenDataLoaderJson(jsonPath, geometry);
            var metadata = new DocumentMetadata(pdfPath.getFileName().toString(), parsed.pageCount(), Optional.empty());
            var docId = "sha256:" + sha256Hex(pdfPath);
            var sections = parsed.sections();
            LOG.debug(
                    "parsed pdf path={} backend=opendataloader pages={} sections={}",
                    pdfPath,
                    parsed.pageCount(),
                    sections.size());
            return new ParsedDocument(docId, sections, metadata);
        } catch (IOException | RuntimeException e) {
            throw new ParseException(
                    "PDF_OPENDATALOADER_PARSE_FAILED",
                    "failed to parse PDF with OpenDataLoader: " + e.getMessage(),
                    pdfPath.toString(),
                    OptionalInt.empty(),
                    e);
        } finally {
            deleteRecursively(outputDir);
        }
    }

    static ParsedOpenDataLoaderJson readOpenDataLoaderJson(Path jsonPath, OpenDataLoaderPdfGeometry geometry)
            throws IOException {
        int pageCount = 0;
        var sections = new ArrayList<ParsedSection>();
        var mapper = new OpenDataLoaderSectionMapper(geometry);
        try (var parser = MAPPER.getFactory().createParser(jsonPath.toFile())) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new IOException("OpenDataLoader JSON is empty: " + jsonPath);
            }
            if (token != JsonToken.START_OBJECT) {
                throw new IOException("OpenDataLoader JSON root must be an object: " + token);
            }
            while (true) {
                token = parser.nextToken();
                if (token == JsonToken.END_OBJECT) {
                    break;
                }
                if (token == null) {
                    throw new IOException("OpenDataLoader JSON ended before the root object closed");
                }
                if (token != JsonToken.FIELD_NAME) {
                    throw new IOException("OpenDataLoader JSON root fields must be named fields: " + token);
                }
                String field = parser.currentName();
                token = parser.nextToken();
                if (token == null) {
                    throw new IOException("OpenDataLoader JSON field has no value: " + field);
                }
                if ("number of pages".equals(field)) {
                    pageCount = parser.getValueAsInt(0);
                } else if ("kids".equals(field)) {
                    if (token != JsonToken.START_ARRAY) {
                        throw new IOException("OpenDataLoader kids field must be an array: " + token);
                    }
                    while (true) {
                        token = parser.nextToken();
                        if (token == JsonToken.END_ARRAY) {
                            break;
                        }
                        if (token == null) {
                            throw new IOException("OpenDataLoader JSON kids array ended before it closed");
                        }
                        if (token != JsonToken.START_OBJECT) {
                            throw new IOException("OpenDataLoader kids entries must be objects: " + token);
                        }
                        mapper.append(MAPPER.readTree(parser), sections);
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return new ParsedOpenDataLoaderJson(pageCount > 0 ? pageCount : geometry.pageCount(), List.copyOf(sections));
    }

    record ParsedOpenDataLoaderJson(int pageCount, List<ParsedSection> sections) {}

    @FunctionalInterface
    interface OpenDataLoaderRunner {
        void process(Path pdfPath, Path outputDir, Config config) throws IOException;
    }

    static AutoCloseable useOpenDataLoaderRunnerForTesting(OpenDataLoaderRunner runner) {
        Objects.requireNonNull(runner, "runner");
        OpenDataLoaderRunner previous;
        synchronized (OPENDATALOADER_LOCK) {
            previous = openDataLoaderRunner;
            openDataLoaderRunner = runner;
        }
        return () -> {
            synchronized (OPENDATALOADER_LOCK) {
                openDataLoaderRunner = previous;
            }
        };
    }

    private static Config config(Path outputDir) {
        var config = new Config();
        config.setOutputFolder(outputDir.toString());
        config.setGenerateJSON(true);
        config.setGenerateMarkdown(false);
        config.setGenerateHtml(false);
        config.setGenerateText(false);
        config.setGeneratePDF(false);
        config.setHybrid(Config.HYBRID_OFF);
        config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
        return config;
    }

    private static void requireRegularFile(Path pdfPath) throws ParseException {
        if (!Files.isRegularFile(pdfPath)) {
            throw new ParseException(
                    "PDF_FILE_NOT_FOUND",
                    "PDF file not found or is not a regular file: " + pdfPath,
                    pdfPath.toString(),
                    OptionalInt.empty());
        }
    }

    private static String jsonFilename(Path pdfPath) {
        String name = pdfPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot) + ".json";
        }
        return name + ".json";
    }

    private static String sha256Hex(Path path) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.debug("failed to delete temporary OpenDataLoader file {}", path, e);
                }
            });
        } catch (IOException e) {
            LOG.debug("failed to inspect temporary OpenDataLoader directory {}", dir, e);
        }
    }
}
