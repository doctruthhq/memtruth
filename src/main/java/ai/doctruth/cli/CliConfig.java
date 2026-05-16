package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

record CliConfig(String provider, Optional<String> model, Path output, Map<String, String> env) {

    static CliConfig load(Map<String, String> env) {
        return load(Path.of("doctruth.yml"), env);
    }

    static CliConfig load(Path path, Map<String, String> env) {
        String provider = "openai";
        Optional<String> model = Optional.empty();
        Path output = Path.of(".doctruth/runs");
        if (Files.exists(path)) {
            var values = readValues(path);
            provider = values.getOrDefault("provider", provider);
            model = Optional.ofNullable(values.get("model")).filter(s -> !s.isBlank());
            output = Path.of(values.getOrDefault("output", output.toString()));
        }
        return new CliConfig(provider, model, output, env);
    }

    private static Map<String, String> readValues(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains(":"))
                    .map(line -> line.split(":", 2))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            parts -> parts[0].trim(), parts -> parts[1].trim(), (a, b) -> b));
        } catch (IOException e) {
            throw new UsageException("failed to read " + path + ": " + e.getMessage());
        }
    }
}
