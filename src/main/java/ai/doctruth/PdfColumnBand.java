package ai.doctruth;

final class PdfColumnBand {

    double x0;
    double x1;

    PdfColumnBand(double x0, double x1) {
        this.x0 = x0;
        this.x1 = x1;
    }

    void include(PdfLineSegment line) {
        x0 = Math.min(x0, line.x0);
        x1 = Math.max(x1, line.x1);
    }

    double width() {
        return Math.max(1.0, x1 - x0);
    }
}
