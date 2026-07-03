package ai.doctruth.cli;

final class CompletionCommand {

    private static final String COMMANDS =
            "init parse ingest-audit benchmark-corpus schema extract audit verify-audit verify-source-map verify-benchmark-report migrate mcp doctor completion version";

    private final CliContext context;

    CompletionCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) {
        if (args.length != 2) {
            throw new UsageException("usage: doctruth completion <bash|zsh|fish>");
        }
        context.out().println(script(args[1]));
    }

    private static String script(String shell) {
        return switch (shell) {
            case "bash" -> bash();
            case "zsh" -> zsh();
            case "fish" -> fish();
            default -> throw new UsageException("unsupported shell: " + shell + "; supported shells: bash, zsh, fish");
        };
    }

    private static String bash() {
        return """
                _doctruth() {
                  local cur="${COMP_WORDS[COMP_CWORD]}"
                  COMPREPLY=( $(compgen -W "%s" -- "$cur") )
                }
                complete -F _doctruth doctruth
                """.formatted(COMMANDS);
    }

    private static String zsh() {
        return """
                #compdef doctruth
                _doctruth() {
                  compadd %s
                }
                _doctruth "$@"
                """.formatted(COMMANDS);
    }

    private static String fish() {
        return """
                complete -c doctruth -f -a "%s"
                """.formatted(COMMANDS);
    }
}
