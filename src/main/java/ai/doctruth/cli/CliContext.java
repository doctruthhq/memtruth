package ai.doctruth.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;

record CliContext(
        Map<String, String> env,
        InputStream in,
        PrintStream out,
        PrintStream err,
        DocTruthCli.PydanticExporter exporter,
        DocTruthCli.ProviderFactory providers) {

    CliContext {
        env = Map.copyOf(Objects.requireNonNull(env, "env"));
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(exporter, "exporter");
        Objects.requireNonNull(providers, "providers");
    }
}
