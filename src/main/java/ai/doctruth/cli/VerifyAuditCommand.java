package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.doctruth.TrustAuditVerifier;
import ai.doctruth.TrustDocument;

final class VerifyAuditCommand {

    private final CliContext context;

    VerifyAuditCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        try {
            var document = TrustDocument.fromJsonFull(Files.readString(options.trustDocument()));
            TrustAuditVerifier.verify(document, Files.readString(options.audit()));
            context.out().println("audit package verified");
        } catch (IOException e) {
            throw new CliException("failed to read audit verification inputs: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new CliException(e.getMessage(), e);
        }
    }

    private record Options(Path trustDocument, Path audit) {
        static Options parse(String[] args) {
            if (args.length != 3) {
                throw new UsageException("usage: doctruth verify-audit <trust-document.json> <audit.json>");
            }
            return new Options(Path.of(args[1]), Path.of(args[2]));
        }
    }
}
