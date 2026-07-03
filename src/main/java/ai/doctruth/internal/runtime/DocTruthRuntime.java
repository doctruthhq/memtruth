package ai.doctruth.internal.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.ParseException;

/**
 * Resolves the local Rust parser runtime used by SDK, CLI, and MCP wrappers.
 */
public final class DocTruthRuntime {

    public static final String PROPERTY = "doctruth.runtime.command";
    public static final String DISABLE_SOURCE_DISCOVERY_PROPERTY = "doctruth.runtime.disableSourceDiscovery";
    public static final String DISABLE_ENVIRONMENT_DISCOVERY_PROPERTY = "doctruth.runtime.disableEnvironmentDiscovery";
    public static final String ENV = "DOCTRUTH_RUNTIME_COMMAND";

    private DocTruthRuntime() {
        throw new AssertionError("no instances");
    }

    public static Optional<Path> configuredCommand() {
        return fromProperty().or(DocTruthRuntime::fromProcessEnv).or(DocTruthRuntime::fromSourceTree);
    }

    public static Optional<Path> configuredCommand(Map<String, String> env) {
        return fromProperty().or(() -> fromEnvMap(env)).or(DocTruthRuntime::fromSourceTree);
    }

    public static Path requireConfiguredCommand(Path sourcePath) throws ParseException {
        return configuredCommand()
                .orElseThrow(() -> new ParseException(
                        "RUST_RUNTIME_NOT_CONFIGURED",
                        "Rust runtime is required. Set DOCTRUTH_RUNTIME_COMMAND or use an installed DocTruth CLI bundle. "
                                + "Select ParserBackendMode.PDFBOX only for explicit Java/PDFBox legacy/oracle mode.",
                        sourcePath.toString(),
                        java.util.OptionalInt.empty()));
    }

    private static Optional<Path> fromProperty() {
        return pathFrom(System.getProperty(PROPERTY, ""));
    }

    private static Optional<Path> fromProcessEnv() {
        if (Boolean.getBoolean(DISABLE_ENVIRONMENT_DISCOVERY_PROPERTY)) {
            return Optional.empty();
        }
        return pathFrom(System.getenv(ENV));
    }

    private static Optional<Path> fromEnvMap(Map<String, String> env) {
        if (Boolean.getBoolean(DISABLE_ENVIRONMENT_DISCOVERY_PROPERTY)) {
            return Optional.empty();
        }
        return pathFrom(env.get(ENV));
    }

    private static Optional<Path> fromSourceTree() {
        if (Boolean.getBoolean(DISABLE_SOURCE_DISCOVERY_PROPERTY)) {
            return Optional.empty();
        }
        return sourceTreeCandidates().stream()
                .filter(path -> path.toFile().isFile())
                .filter(path -> path.toFile().canExecute())
                .findFirst();
    }

    private static List<Path> sourceTreeCandidates() {
        return List.of(
                Path.of("runtime/doctruth-runtime/target/debug/doctruth-runtime"),
                Path.of("runtime/doctruth-runtime/target/release/doctruth-runtime"));
    }

    private static Optional<Path> pathFrom(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value.trim()));
    }
}
