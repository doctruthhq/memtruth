package ai.doctruth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

final class PdfPageImages {

    private static final float PAGE_IMAGE_DPI = 72f;

    private PdfPageImages() {
        throw new AssertionError("no instances");
    }

    static List<TrustPage> renderedPages(Path pdfPath) throws ParseException {
        return render(pdfPath, null);
    }

    static List<TrustPage> writePngs(Path pdfPath, Path outputDir) throws ParseException {
        return render(pdfPath, outputDir);
    }

    private static List<TrustPage> render(Path pdfPath, Path outputDir) throws ParseException {
        try (var pdf = Loader.loadPDF(pdfPath.toFile())) {
            if (outputDir != null) {
                Files.createDirectories(outputDir);
            }
            var renderer = new PDFRenderer(pdf);
            var pages = new ArrayList<TrustPage>(pdf.getNumberOfPages());
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                pages.add(renderedPage(renderer, i, outputDir));
            }
            return List.copyOf(pages);
        } catch (IOException e) {
            throw new ParseException(
                    "PDF_PAGE_IMAGE_RENDER_FAILED",
                    "failed to render PDF page image: " + e.getMessage(),
                    pdfPath.toString(),
                    java.util.OptionalInt.empty(),
                    e);
        }
    }

    private static TrustPage renderedPage(PDFRenderer renderer, int pageIndex, Path outputDir) throws IOException {
        var image = renderer.renderImageWithDPI(pageIndex, PAGE_IMAGE_DPI);
        byte[] png = pngBytes(image);
        if (outputDir != null) {
            Files.write(outputDir.resolve("page-%04d.png".formatted(pageIndex + 1)), png);
        }
        return new TrustPage(pageIndex + 1, image.getWidth(), image.getHeight(), true, imageHash(png));
    }

    private static byte[] pngBytes(java.awt.image.BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("PNG writer unavailable");
        }
        return bytes.toByteArray();
    }

    private static String imageHash(byte[] png) {
        return "sha256:" + HexFormat.of().formatHex(sha256().digest(png));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }
}
