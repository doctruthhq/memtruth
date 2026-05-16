package ai.doctruth.cli;

class CliException extends Exception {
    private static final long serialVersionUID = 1L;

    CliException(String message) {
        super(message);
    }

    CliException(String message, Throwable cause) {
        super(message, cause);
    }
}
