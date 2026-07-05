package ai.doctruth.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ai.doctruth.ParsedDocument;
import ai.doctruth.PdfParserBackend;
import ai.doctruth.TrustDocumentJson;

final class ProfileCommand {

    private final CliContext context;

    ProfileCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = ProfileOptions.parse(args);
        var result = measure(options);
        if (options.json()) {
            context.out().println(result.toJson());
            return;
        }
        context.out().println(options.document());
        context.out().println("parser: " + result.parser());
        context.out().println("iterations: " + result.iterations());
        context.out().println("file size bytes: " + result.fileSizeBytes());
        context.out().println("sections: " + result.sectionCount());
        context.out().println("include output: " + result.includeOutput().id());
        context.out().println("cold latency ms: " + result.coldLatencyMillis());
        context.out().println("warm avg latency ms: " + result.warmAverageLatencyMillis());
        if (result.includeOutput() != IncludeOutput.PARSER_ONLY) {
            context.out().println("cold output latency ms: " + result.coldOutputLatencyMillis());
            context.out().println("warm avg output latency ms: " + result.warmAverageOutputLatencyMillis());
        }
        context.out().println("heap used before bytes: " + result.heapUsedBeforeBytes());
        context.out().println("heap used after bytes: " + result.heapUsedAfterBytes());
    }

    private static ProfileResult measure(ProfileOptions options) throws CliException {
        long fileSize = fileSize(options.document());
        long heapBefore = usedHeap();
        var parseLatencies = new ArrayList<Long>(options.iterations());
        var outputLatencies = new ArrayList<Long>(options.iterations());
        int sectionCount = 0;
        long outputChars = 0;
        long outputBytes = 0;
        for (int i = 0; i < options.iterations(); i++) {
            long start = System.nanoTime();
            ParsedDocument doc = DocumentParsers.parse(options.document(), options.parser());
            parseLatencies.add(millisSince(start));
            sectionCount = doc.sections().size();
            if (options.includeOutput() != IncludeOutput.PARSER_ONLY) {
                long outputStart = System.nanoTime();
                var output = renderOutput(doc, options);
                outputChars += output.chars();
                outputBytes += output.bytes();
                outputLatencies.add(millisSince(outputStart));
            }
        }
        long heapAfter = usedHeap();
        return new ProfileResult(
                options.parser().id(),
                options.iterations(),
                fileSize,
                sectionCount,
                options.includeOutput(),
                copyOf(parseLatencies),
                copyOf(outputLatencies),
                outputChars,
                outputBytes,
                heapBefore,
                heapAfter);
    }

    private static OutputMeasurement renderOutput(ParsedDocument doc, ProfileOptions options) throws CliException {
        return switch (options.includeOutput()) {
            case PARSER_ONLY -> new OutputMeasurement(0, 0);
            case TRUST_JSON ->
                new OutputMeasurement(
                        TrustDocumentJson.toJson(doc, options.document(), options.parser())
                                .length(),
                        0);
            case PARSED_JSON ->
                new OutputMeasurement(ParsedDocumentJson.toJson(doc).length(), 0);
            case TRUST_JSON_FILE ->
                renderFileOutput(
                        output -> TrustDocumentJson.writeJson(doc, options.document(), options.parser(), output));
            case PARSED_JSON_FILE -> renderFileOutput(output -> ParsedDocumentJson.writeJson(doc, output));
        };
    }

    private static OutputMeasurement renderFileOutput(FileOutputWriter writer) throws CliException {
        Path output = null;
        try {
            output = Files.createTempFile("doctruth-profile-output-", ".json");
            writer.write(output);
            return new OutputMeasurement(0, Files.size(output));
        } catch (CliException e) {
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            throw new CliException("failed to render profile output: " + e.getMessage(), e);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (java.io.IOException e) {
                    // best effort cleanup for a temporary profile artifact
                }
            }
        }
    }

    private static long fileSize(Path path) throws CliException {
        try {
            return Files.size(path);
        } catch (java.io.IOException e) {
            throw new CliException("failed to inspect profile document: " + e.getMessage(), e);
        }
    }

    private static long[] copyOf(List<Long> values) {
        long[] copy = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            copy[i] = values.get(i);
        }
        return copy;
    }

    private static long millisSince(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @FunctionalInterface
    private interface FileOutputWriter {
        void write(Path output) throws java.io.IOException, CliException;
    }

    private record OutputMeasurement(long chars, long bytes) {}

    private record ProfileOptions(
            Path document, PdfParserBackend parser, int iterations, boolean json, IncludeOutput includeOutput) {
        static ProfileOptions parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException(
                        "usage: doctruth profile <document> [--parser opendataloader|pdfbox] [--iterations n] [--include-output parser-only|trust-json|parsed-json] [--json]");
            }
            Path document = Path.of(args[1]);
            PdfParserBackend parser = PdfParserBackend.OPENDATALOADER;
            int iterations = 3;
            boolean json = false;
            IncludeOutput includeOutput = IncludeOutput.PARSER_ONLY;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--json" -> json = true;
                    case "--parser" -> parser = parseBackend(nextValue(cursor, arg));
                    case "--iterations" -> iterations = parseIterations(nextValue(cursor, arg));
                    case "--include-output" -> includeOutput = IncludeOutput.parse(nextValue(cursor, arg));
                    default -> throw new UsageException("unknown profile option: " + arg);
                }
            }
            return new ProfileOptions(document, parser, iterations, json, includeOutput);
        }

        private static PdfParserBackend parseBackend(String raw) {
            try {
                return PdfParserBackend.fromId(raw);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }

        private static int parseIterations(String raw) {
            try {
                int value = Integer.parseInt(raw);
                if (value < 1) {
                    throw new UsageException("--iterations must be >= 1");
                }
                return value;
            } catch (NumberFormatException e) {
                throw new UsageException("--iterations must be an integer");
            }
        }

        private static String nextValue(ArgCursor cursor, String option) {
            if (!cursor.hasNext()) {
                throw new UsageException(option + " requires a value");
            }
            return cursor.next();
        }
    }
}
