package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class InitCommand {

    private static final String CONFIG = """
            provider: openai
            model: gpt-4o
            output: .doctruth/runs
            citation:
              require: true
              minMatchScore: 0.85
            """;

    private final CliContext context;

    InitCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        Path dir = parseDir(args);
        try {
            Files.createDirectories(dir.resolve("schemas"));
            Files.createDirectories(dir.resolve(".doctruth/runs"));
            Path config = dir.resolve("doctruth.yml");
            if (!Files.exists(config)) {
                Files.writeString(config, CONFIG);
            }
            context.out().println("initialized: " + dir);
            context.out().println("config: " + config);
        } catch (IOException e) {
            throw new CliException("failed to initialize DocTruth project: " + e.getMessage(), e);
        }
    }

    private static Path parseDir(String[] args) {
        Path dir = Path.of(".");
        var cursor = new ArgCursor(args, 1);
        while (cursor.hasNext()) {
            String arg = cursor.next();
            if ("--dir".equals(arg)) {
                dir = cursor.nextPath("--dir");
            } else {
                throw new UsageException("unknown init option: " + arg);
            }
        }
        return dir;
    }
}
