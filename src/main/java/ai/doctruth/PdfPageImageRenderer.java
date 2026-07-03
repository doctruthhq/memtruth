package ai.doctruth;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Renders PDF pages to deterministic PNG artifacts for review and replay tools.
 *
 * @since 1.0.0
 */
public final class PdfPageImageRenderer {

    private PdfPageImageRenderer() {
        throw new AssertionError("no instances");
    }

    public static List<TrustPage> writePngs(Path pdfPath, Path outputDir) throws ParseException {
        var source = Objects.requireNonNull(pdfPath, "pdfPath");
        var out = Objects.requireNonNull(outputDir, "outputDir");
        return PdfPageImages.writePngs(source, out);
    }
}
