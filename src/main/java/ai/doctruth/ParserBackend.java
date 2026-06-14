package ai.doctruth;

/**
 * Parser backend boundary for the Rust runtime and explicit legacy/oracle adapters.
 *
 * @since 1.0.0
 */
public interface ParserBackend {

    TrustDocument parse(ParserRequest request) throws ParseException;

    ParserCapabilities capabilities();

    ParserHealth doctor();
}
