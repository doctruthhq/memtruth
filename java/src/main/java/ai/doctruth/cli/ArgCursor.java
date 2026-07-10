package ai.doctruth.cli;

import java.nio.file.Path;

final class ArgCursor {

    private final String[] args;
    private int index;

    ArgCursor(String[] args, int start) {
        this.args = args.clone();
        this.index = start;
    }

    boolean hasNext() {
        return index < args.length;
    }

    String next() {
        if (!hasNext()) {
            throw new UsageException("missing argument");
        }
        return args[index++];
    }

    Path nextPath(String option) {
        if (!hasNext()) {
            throw new UsageException(option + " requires a path");
        }
        return Path.of(next());
    }
}
