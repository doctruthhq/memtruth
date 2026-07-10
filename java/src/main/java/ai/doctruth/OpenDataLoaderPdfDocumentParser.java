package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OpenDataLoaderPdfDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(OpenDataLoaderPdfDocumentParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        suppressOpenDataLoaderInfoLogging();
    }

    private OpenDataLoaderPdfDocumentParser() {
        throw new AssertionError("no instances");
    }

    private static void suppressOpenDataLoaderInfoLogging() {
        for (String name : List.of(
                "org.opendataloader",
                "org.opendataloader.pdf",
                "org.opendataloader.pdf.processors",
                "org.opendataloader.pdf.processors.DocumentProcessor",
                "org.opendataloader.pdf.json",
                "org.opendataloader.pdf.json.JsonWriter")) {
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(name);
            logger.setLevel(java.util.logging.Level.WARNING);
            logger.setUseParentHandlers(false);
        }
    }

    static ParsedDocument parse(Path pdfPath) throws ParseException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        requireRegularFile(pdfPath);
        Path outputDir = null;
        try {
            outputDir = Files.createTempDirectory("doctruth-opendataloader-");
            var geometry = OpenDataLoaderPdfGeometry.read(pdfPath);
            Config config = config(outputDir);
            try (var suppression = JulInfoSuppression.open()) {
                suppression.keepAlive();
                OpenDataLoaderPDF.processFile(pdfPath.toString(), config);
            }
            Path jsonPath = outputDir.resolve(jsonFilename(pdfPath));
            if (!Files.isRegularFile(jsonPath)) {
                throw new IOException("OpenDataLoader did not produce JSON output: " + jsonPath);
            }
            JsonNode root = MAPPER.readTree(jsonPath.toFile());
            int pageCount = root.path("number of pages").asInt(geometry.pageCount());
            var metadata = new DocumentMetadata(pdfPath.getFileName().toString(), pageCount, Optional.empty());
            var docId = "sha256:" + sha256Hex(pdfPath);
            var sections = new OpenDataLoaderSectionMapper(geometry).map(root.path("kids"));
            LOG.debug(
                    "parsed pdf path={} backend=opendataloader pages={} sections={}",
                    pdfPath,
                    pageCount,
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
            OpenDataLoaderPDF.shutdown();
            deleteRecursively(outputDir);
        }
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

    private static final class JulInfoSuppression implements AutoCloseable {
        private final java.util.logging.Logger root;
        private final java.util.logging.Level rootLevel;
        private final java.util.logging.Handler[] handlers;
        private final java.util.logging.Level[] handlerLevels;

        private JulInfoSuppression(
                java.util.logging.Logger root,
                java.util.logging.Level rootLevel,
                java.util.logging.Handler[] handlers,
                java.util.logging.Level[] handlerLevels) {
            this.root = root;
            this.rootLevel = rootLevel;
            this.handlers = handlers;
            this.handlerLevels = handlerLevels;
        }

        static JulInfoSuppression open() {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            java.util.logging.Handler[] handlers = root.getHandlers();
            java.util.logging.Level[] handlerLevels = new java.util.logging.Level[handlers.length];
            for (int i = 0; i < handlers.length; i++) {
                handlerLevels[i] = handlers[i].getLevel();
                handlers[i].setLevel(java.util.logging.Level.WARNING);
            }
            java.util.logging.Level rootLevel = root.getLevel();
            root.setLevel(java.util.logging.Level.WARNING);
            return new JulInfoSuppression(root, rootLevel, handlers, handlerLevels);
        }

        void keepAlive() {
            // Referenced to keep javac -Xlint:try satisfied for this scoped guard.
        }

        @Override
        public void close() {
            root.setLevel(rootLevel);
            for (int i = 0; i < handlers.length; i++) {
                handlers[i].setLevel(handlerLevels[i]);
            }
        }
    }
}
