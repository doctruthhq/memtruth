package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class VerifySourceMapCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    VerifySourceMapCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        var map = readMap(options.sourceMap());
        verifyContent(options.rendered(), map);
        if (options.source() != null) {
            verifySource(options.source(), map);
        }
        context.out().println("source map verified");
    }

    private static void verifyContent(Path rendered, JsonNode map) throws CliException {
        String expected = requiredText(map, "contentHash");
        String actual = sha256RenderedTextFile(rendered);
        if (!expected.equals(actual)) {
            throw new CliException("content hash mismatch: expected " + expected + " actual " + actual);
        }
    }

    private static void verifySource(Path source, JsonNode map) throws CliException {
        String expected = requiredText(map, "sourceHash");
        String actual = sha256SourceFile(source);
        if (!expected.equals(actual)) {
            throw new CliException("source hash mismatch: expected " + expected + " actual " + actual);
        }
    }

    private static JsonNode readMap(Path path) throws CliException {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new CliException("failed to read source map: " + e.getMessage(), e);
        }
    }

    private static String requiredText(JsonNode map, String field) throws CliException {
        String value = map.path(field).asText();
        if (value.isBlank()) {
            throw new CliException("source map missing " + field);
        }
        return value;
    }

    static String sha256RenderedTextFile(Path path) throws CliException {
        return sha256File(path, "rendered document");
    }

    static String sha256SourceFile(Path path) throws CliException {
        return sha256File(path, "source document");
    }

    private static String sha256File(Path path, String label) throws CliException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new CliException("failed to hash " + label + ": " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new CliException("SHA-256 is unavailable", e);
        }
    }

    private record Options(Path rendered, Path sourceMap, Path source) {
        static Options parse(String[] args) {
            if (args.length < 3) {
                throw new UsageException(
                        "usage: doctruth verify-source-map <rendered> <map.json> [--source <document>]");
            }
            Path rendered = Path.of(args[1]);
            Path sourceMap = Path.of(args[2]);
            Path source = null;
            var cursor = new ArgCursor(args, 3);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--source" -> source = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown verify-source-map option: " + arg);
                }
            }
            return new Options(rendered, sourceMap, source);
        }
    }
}
