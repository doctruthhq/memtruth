package ai.doctruth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

record OpenDataLoaderPdfGeometry(Map<Integer, PageGeometry> pages) {

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

    int pageCount() {
        return pages.size();
    }

    Optional<PageGeometry> page(int pageNumber) {
        return Optional.ofNullable(pages.get(pageNumber));
    }

    record PageGeometry(double width, double height) {}
}
