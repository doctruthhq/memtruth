package ai.doctruth.cli;

final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
