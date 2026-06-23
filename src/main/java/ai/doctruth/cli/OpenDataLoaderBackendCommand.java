package ai.doctruth.cli;

import ai.doctruth.opendataloader.OpenDataLoaderBackendCli;

final class OpenDataLoaderBackendCommand {

    private final CliContext context;

    OpenDataLoaderBackendCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) {
        if (args.length != 2 || !"--stdio-jsonl".equals(args[1])) {
            throw new UsageException("usage: doctruth opendataloader-backend --stdio-jsonl");
        }
        int code = OpenDataLoaderBackendCli.run(context.in(), context.out());
        if (code != 0) {
            throw new UsageException("opendataloader-backend failed");
        }
    }
}
