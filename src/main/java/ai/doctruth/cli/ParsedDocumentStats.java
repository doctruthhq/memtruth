package ai.doctruth.cli;

import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

record ParsedDocumentStats(int sections, int text, int tables, int figures, int textWithBbox) {

    static ParsedDocumentStats from(ParsedDocument doc) {
        int text = 0;
        int tables = 0;
        int figures = 0;
        int boxes = 0;
        for (var section : doc.sections()) {
            switch (section) {
                case TextSection t -> {
                    text++;
                    if (t.boundingBox().isPresent()) {
                        boxes++;
                    }
                }
                case TableSection ignored -> tables++;
                case FigureSection ignored -> figures++;
            }
        }
        return new ParsedDocumentStats(doc.sections().size(), text, tables, figures, boxes);
    }
}
