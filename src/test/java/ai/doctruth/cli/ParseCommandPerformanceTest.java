package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ai.doctruth.DocumentMetadata;
import ai.doctruth.ParsedDocument;
import ai.doctruth.PdfParserBackend;

import org.junit.jupiter.api.Test;

class ParseCommandPerformanceTest {

    @Test
    void summaryPathDoesNotSerializeJson() throws Exception {
        var serializerCalls = new AtomicInteger();
        var out = new ByteArrayOutputStream();
        var context = new CliContext(
                Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                spec -> "{}",
                opts -> {
                    throw new AssertionError("provider is not used by parse");
                });
        var command = new ParseCommand(
                context, (path, backend) -> parsed(path), countingRenderer(serializerCalls, new AtomicInteger()));

        command.run(new String[] {"parse", "contract.pdf"});

        assertThat(serializerCalls).hasValue(0);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("parser: opendataloader");
    }

    @Test
    void jsonOutputSerializesOnce() throws Exception {
        var serializerCalls = new AtomicInteger();
        var context = new CliContext(
                Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                spec -> "{}",
                opts -> {
                    throw new AssertionError("provider is not used by parse");
                });
        var command = new ParseCommand(
                context, (path, backend) -> parsed(path), countingRenderer(serializerCalls, new AtomicInteger()));

        command.run(new String[] {"parse", "contract.pdf", "--format", "json"});

        assertThat(serializerCalls).hasValue(1);
    }

    @Test
    void jsonFileOutputWritesWithoutStringMaterialization() throws Exception {
        var renderCalls = new AtomicInteger();
        var writeCalls = new AtomicInteger();
        Path output = Files.createTempFile("doctruth-trust-document", ".json");
        var context = new CliContext(
                Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                spec -> "{}",
                opts -> {
                    throw new AssertionError("provider is not used by parse");
                });
        var command = new ParseCommand(context, (path, backend) -> parsed(path), new ParseCommand.JsonRenderer() {
            @Override
            public String render(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument) {
                renderCalls.incrementAndGet();
                return "{}";
            }

            @Override
            public void write(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument, Path out)
                    throws CliException {
                writeCalls.incrementAndGet();
                try {
                    Files.writeString(out, "{}");
                } catch (java.io.IOException e) {
                    throw new CliException("write failed", e);
                }
            }
        });

        command.run(new String[] {"parse", "contract.pdf", "--format", "json", "-o", output.toString()});

        assertThat(renderCalls).hasValue(0);
        assertThat(writeCalls).hasValue(1);
    }

    private static ParsedDocument parsed(Path path) {
        return new ParsedDocument(
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                List.of(),
                new DocumentMetadata(path.getFileName().toString(), 1, java.util.Optional.empty()));
    }

    private static ParseCommand.JsonRenderer countingRenderer(AtomicInteger renderCalls, AtomicInteger writeCalls) {
        return new ParseCommand.JsonRenderer() {
            @Override
            public String render(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument) {
                renderCalls.incrementAndGet();
                return "{}";
            }

            @Override
            public void write(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument, Path out)
                    throws CliException {
                writeCalls.incrementAndGet();
                try {
                    Files.writeString(out, "{}");
                } catch (java.io.IOException e) {
                    throw new CliException("write failed", e);
                }
            }
        };
    }
}
