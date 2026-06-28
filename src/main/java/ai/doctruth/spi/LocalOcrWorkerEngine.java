package ai.doctruth.spi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local OCR engine backed by the JSON-over-stdin/stdout worker protocol used by the
 * desktop sidecars. The worker can be RapidOCR+MNN, RapidOCR+ONNXRuntime, or the Rust
 * MNN worker as long as it accepts the same request/response shape.
 *
 * @since 0.2.0
 */
public final class LocalOcrWorkerEngine implements OcrEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LocalOcrWorkerEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_CHARS = 8 * 1024;

    private final String command;
    private final String engine;
    private final String fallbackEngine;
    private final Duration timeout;

    public LocalOcrWorkerEngine(String command) {
        this(command, "mnn", "onnxruntime", 30_000);
    }

    public LocalOcrWorkerEngine(String command, String engine, String fallbackEngine, long timeoutMs) {
        this.command = requireNonBlank(command, "command");
        this.engine = normalizeEngine(engine);
        this.fallbackEngine = normalizeEngine(fallbackEngine);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0");
        }
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public OcrPageResult ocr(BufferedImage pageImage, int pageNumber) {
        Objects.requireNonNull(pageImage, "pageImage");
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1");
        }
        try {
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            process.getOutputStream().write(requestJson(pageImage, pageNumber).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("local OCR worker timed out command={} page={}", command, pageNumber);
                return OcrPageResult.empty(pageNumber);
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = redact(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            JsonNode response = MAPPER.readTree(extractJsonObject(stdout));
            if (!response.path("ok").asBoolean(false)) {
                LOG.warn(
                        "local OCR worker failed command={} page={} message={} stderr={}",
                        command,
                        pageNumber,
                        response.path("message").asText("unknown"),
                        stderr);
                return OcrPageResult.empty(pageNumber);
            }
            return toResult(response, pageNumber);
        } catch (IOException e) {
            LOG.warn("local OCR worker unavailable command={} page={} message={}", command, pageNumber, e.getMessage());
            return OcrPageResult.empty(pageNumber);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("local OCR worker interrupted command={} page={}", command, pageNumber);
            return OcrPageResult.empty(pageNumber);
        } catch (RuntimeException e) {
            LOG.warn(
                    "local OCR worker returned unusable output command={} page={} message={}",
                    command,
                    pageNumber,
                    e.getMessage());
            return OcrPageResult.empty(pageNumber);
        }
    }

    private String requestJson(BufferedImage pageImage, int pageNumber) throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("version", 1);
        request.put("engine", engine);
        request.put("fallbackEngine", fallbackEngine);
        request.put("renderMaxWidth", Math.max(320, pageImage.getWidth()));
        request.put("maxPages", 1);
        request.put("fileName", "page-" + pageNumber + ".png");
        request.put("fileType", "png");
        request.put("mimeType", "image/png");
        request.putNull("tenantId");
        request.put("bytesBase64", Base64.getEncoder().encodeToString(pngBytes(pageImage)));
        return MAPPER.writeValueAsString(request);
    }

    private static byte[] pngBytes(BufferedImage pageImage) throws IOException {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(pageImage, "png", out)) {
            throw new IOException("PNG encoder not available");
        }
        return out.toByteArray();
    }

    private static OcrPageResult toResult(JsonNode response, int pageNumber) {
        String text = response.path("text").asText("").strip();
        if (text.isBlank()) {
            text = textFromPages(response.path("pages"));
        }
        if (text.isBlank()) {
            return OcrPageResult.empty(pageNumber);
        }
        double confidence = confidence(response);
        return new OcrPageResult(text, confidence, regions(response.path("pages")), pageNumber);
    }

    private static String textFromPages(JsonNode pages) {
        if (!pages.isArray()) {
            return "";
        }
        var lines = new StringBuilder();
        for (JsonNode page : pages) {
            String text = page.path("text").asText("").strip();
            if (text.isEmpty()) {
                continue;
            }
            if (!lines.isEmpty()) {
                lines.append("\n\n");
            }
            lines.append(text);
        }
        return lines.toString();
    }

    private static double confidence(JsonNode response) {
        JsonNode average = response.path("averageConfidence");
        if (average.isNumber()) {
            return clampConfidence(average.asDouble());
        }
        JsonNode pages = response.path("pages");
        if (!pages.isArray()) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (JsonNode page : pages) {
            JsonNode value = page.path("confidence");
            if (value.isNumber()) {
                sum += clampConfidence(value.asDouble());
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static List<OcrRegion> regions(JsonNode pages) {
        if (!pages.isArray()) {
            return List.of();
        }
        var out = new ArrayList<OcrRegion>();
        for (JsonNode page : pages) {
            JsonNode regions = page.path("regions");
            if (!regions.isArray()) {
                continue;
            }
            for (JsonNode region : regions) {
                region(region).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    private static java.util.Optional<OcrRegion> region(JsonNode region) {
        var box = box(region.path("bbox"));
        if (box.isEmpty()) {
            box = box(region.path("box"));
        }
        return box.map(value -> new OcrRegion(
                region.path("text").asText(""),
                value,
                clampConfidence(region.path("confidence").asDouble(0.0))));
    }

    private static java.util.Optional<OcrBox> box(JsonNode value) {
        if (value.isObject()) {
            return positiveBox(
                    value.path("x").asInt(-1),
                    value.path("y").asInt(-1),
                    value.path("width").asInt(-1),
                    value.path("height").asInt(-1));
        }
        if (value.isArray() && value.size() >= 4) {
            return positiveBox(
                    value.get(0).asInt(-1),
                    value.get(1).asInt(-1),
                    value.get(2).asInt(-1),
                    value.get(3).asInt(-1));
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<OcrBox> positiveBox(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new OcrBox(x, y, width, height));
    }

    private static double clampConfidence(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    static String extractJsonObject(String stdout) {
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty OCR worker stdout");
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("OCR worker stdout did not contain JSON");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = start; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("OCR worker stdout JSON was incomplete");
    }

    private static String normalizeEngine(String value) {
        String normalized = requireNonBlank(value, "engine").toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("mnn") && !normalized.equals("onnxruntime")) {
            throw new IllegalArgumentException("unsupported OCR engine: " + value);
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String redact(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String trimmed = stderr.strip();
        if (trimmed.length() <= MAX_STDERR_CHARS) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - MAX_STDERR_CHARS);
    }
}
