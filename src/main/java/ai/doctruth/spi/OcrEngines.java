package ai.doctruth.spi;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Factories for OCR engines that keep the parser boundary stable while allowing local
 * desktop deployments to wire in bundled workers and models.
 *
 * @since 0.2.0
 */
public final class OcrEngines {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private OcrEngines() {
        throw new AssertionError("no instances");
    }

    public static OcrEngine noop() {
        return OcrEngine.NOOP;
    }

    /**
     * Discover a local OCR worker. Resolution order:
     *
     * <ol>
     *   <li>{@code doctruth.ocr.command} system property.
     *   <li>{@code DOCTRUTH_OCR_COMMAND} environment variable.
     *   <li>{@code LOCAL_OCR_COMMAND} environment variable.
     *   <li>{@code doctruth-rapidocr-mnn-worker}, {@code tradebot-ocr-worker-rs}, or
     *       {@code tradebot-ocr-worker} on {@code PATH}.
     * </ol>
     *
     * <p>If no executable command is found, returns {@link OcrEngine#NOOP}. This keeps
     * normal text-layer parsing dependency-free while enabling scanned-page OCR when the
     * desktop app ships a worker.
     */
    public static OcrEngine defaultLocal() {
        if (isDisabled()) {
            return OcrEngine.NOOP;
        }
        Optional<String> command = firstExecutable(commandCandidates());
        if (command.isEmpty()) {
            return OcrEngine.NOOP;
        }
        OcrEngine primary = worker(command.get());
        Optional<String> fallbackCommand = firstExecutable(fallbackCommandCandidates());
        if (fallbackCommand.isPresent() && !fallbackCommand.get().equals(command.get())) {
            return new FallbackOcrEngine(primary, worker(fallbackCommand.get()));
        }
        return primary;
    }

    public static OcrEngine worker(String command) {
        return new LocalOcrWorkerEngine(
                command,
                setting("doctruth.ocr.engine", "DOCTRUTH_OCR_ENGINE", "LOCAL_OCR_ENGINE").orElse("mnn"),
                setting("doctruth.ocr.fallbackEngine", "DOCTRUTH_OCR_FALLBACK_ENGINE", "LOCAL_OCR_FALLBACK_ENGINE")
                        .orElse("onnxruntime"),
                timeoutMs());
    }

    private static boolean isDisabled() {
        return setting("doctruth.ocr.enabled", "DOCTRUTH_OCR_ENABLED", "LOCAL_OCR_ENABLED")
                .map(value -> value.equalsIgnoreCase("false") || value.equals("0"))
                .orElse(false);
    }

    private static List<String> commandCandidates() {
        var out = new ArrayList<String>();
        setting("doctruth.ocr.command", "DOCTRUTH_OCR_COMMAND", "LOCAL_OCR_COMMAND").ifPresent(out::add);
        out.add("doctruth-rapidocr-mnn-worker");
        out.add("tradebot-ocr-worker-rs");
        out.add("tradebot-ocr-worker");
        return out;
    }

    private static List<String> fallbackCommandCandidates() {
        var out = new ArrayList<String>();
        setting("doctruth.ocr.fallbackCommand", "DOCTRUTH_OCR_FALLBACK_COMMAND", "LOCAL_OCR_FALLBACK_COMMAND")
                .ifPresent(out::add);
        return out;
    }

    private static long timeoutMs() {
        return setting("doctruth.ocr.timeoutMs", "DOCTRUTH_OCR_TIMEOUT_MS", "LOCAL_OCR_TIMEOUT_MS")
                .flatMap(OcrEngines::parsePositiveLong)
                .orElse(DEFAULT_TIMEOUT_MS);
    }

    private static Optional<Long> parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> setting(String property, String primaryEnv, String secondaryEnv) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Optional.of(fromProperty.strip());
        }
        String fromPrimaryEnv = System.getenv(primaryEnv);
        if (fromPrimaryEnv != null && !fromPrimaryEnv.isBlank()) {
            return Optional.of(fromPrimaryEnv.strip());
        }
        String fromSecondaryEnv = System.getenv(secondaryEnv);
        if (fromSecondaryEnv != null && !fromSecondaryEnv.isBlank()) {
            return Optional.of(fromSecondaryEnv.strip());
        }
        return Optional.empty();
    }

    private static Optional<String> firstExecutable(List<String> commands) {
        for (String command : commands) {
            Optional<String> resolved = resolveExecutable(command);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> resolveExecutable(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        String trimmed = command.strip();
        if (trimmed.contains("/") || trimmed.startsWith(".")) {
            Path path = Path.of(trimmed);
            return Files.isRegularFile(path) && Files.isExecutable(path) ? Optional.of(trimmed) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, trimmed);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate.toString());
            }
            if (isWindows()) {
                for (String extension : windowsExtensions()) {
                    Path withExtension = Path.of(dir, trimmed + extension);
                    if (Files.isRegularFile(withExtension) && Files.isExecutable(withExtension)) {
                        return Optional.of(withExtension.toString());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<String> windowsExtensions() {
        String pathext = System.getenv().getOrDefault("PATHEXT", ".EXE;.CMD;.BAT;.COM");
        return List.of(pathext.split(";"));
    }

    private record FallbackOcrEngine(OcrEngine primary, OcrEngine fallback) implements OcrEngine {
        @Override
        public OcrPageResult ocr(BufferedImage pageImage, int pageNumber) {
            OcrPageResult result = primary.ocr(pageImage, pageNumber);
            if (!result.text().isBlank()) {
                return result;
            }
            return fallback.ocr(pageImage, pageNumber);
        }
    }
}
