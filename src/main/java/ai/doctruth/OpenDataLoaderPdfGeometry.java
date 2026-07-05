package ai.doctruth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

final class OpenDataLoaderPdfGeometry {

    private final Map<Integer, PageGeometry> pages;
    private final GeometryLoader loader;
    private OpenDataLoaderPdfGeometry loaded;

    OpenDataLoaderPdfGeometry(Map<Integer, PageGeometry> pages) {
        this.pages = Map.copyOf(Objects.requireNonNull(pages, "pages"));
        this.loader = null;
    }

    private OpenDataLoaderPdfGeometry(GeometryLoader loader) {
        this.pages = null;
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    static OpenDataLoaderPdfGeometry read(Path pdfPath) throws IOException {
        var pages = new HashMap<Integer, PageGeometry>();
        try (var pdf = Loader.loadPDF(pdfPath.toFile())) {
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                PDRectangle cropBox = pdf.getPage(i).getCropBox();
                pages.put(i + 1, new PageGeometry(cropBox.getWidth(), cropBox.getHeight()));
            }
        }
        return new OpenDataLoaderPdfGeometry(pages);
    }

    static OpenDataLoaderPdfGeometry lazy(Path pdfPath) {
        return lazy(() -> read(pdfPath));
    }

    static OpenDataLoaderPdfGeometry lazy(GeometryLoader loader) {
        return new OpenDataLoaderPdfGeometry(loader);
    }

    int pageCount() {
        return materialized().pages.size();
    }

    Optional<PageGeometry> page(int pageNumber) {
        return Optional.ofNullable(materialized().pages.get(pageNumber));
    }

    private synchronized OpenDataLoaderPdfGeometry materialized() {
        if (loader == null) {
            return this;
        }
        if (loaded == null) {
            try {
                loaded = loader.load();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read PDF page geometry", e);
            }
        }
        return loaded;
    }

    @FunctionalInterface
    interface GeometryLoader {
        OpenDataLoaderPdfGeometry load() throws IOException;
    }

    record PageGeometry(double width, double height) {}
}
