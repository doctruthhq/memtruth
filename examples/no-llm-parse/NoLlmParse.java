// SPDX-License-Identifier: Apache-2.0
package ai.doctruth.examples.nollmparse;

import ai.doctruth.CsvDocumentParser;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;
import java.nio.file.Path;

/** Keyless parser smoke for the public evidence-anchor surface. */
public final class NoLlmParse {

    private NoLlmParse() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws Exception {
        Path source = args.length == 0
                ? Path.of("examples/no-llm-parse/sample-contract.csv")
                : Path.of(args[0]);
        ParsedDocument document = CsvDocumentParser.parse(source);
        Stats stats = Stats.from(document);

        System.out.println(source);
        System.out.println("docId: " + document.docId());
        System.out.println("pages: " + document.metadata().pageCount());
        System.out.println("sections: " + document.sections().size());
        System.out.println("text: " + stats.text());
        System.out.println("tables: " + stats.tables());
        System.out.println("figures: " + stats.figures());
        System.out.println();
        System.out.println("First evidence anchor:");
        document.sections().stream().findFirst().ifPresent(NoLlmParse::printAnchor);
    }

    private static void printAnchor(ai.doctruth.ParsedSection section) {
        switch (section) {
            case TextSection text -> System.out.printf(
                    "  text page=%d line=%d quote=%s%n",
                    text.location().pageStart(), text.location().lineStart(), abbreviate(text.text()));
            case TableSection table -> System.out.printf(
                    "  table page=%d rows=%d firstRow=%s%n",
                    table.location().pageStart(), table.rows().size(), table.rows().getFirst());
            case FigureSection figure -> System.out.printf(
                    "  figure page=%d caption=%s%n",
                    figure.location().pageStart(), abbreviate(figure.caption()));
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private record Stats(int text, int tables, int figures) {
        static Stats from(ParsedDocument document) {
            int text = 0;
            int tables = 0;
            int figures = 0;
            for (var section : document.sections()) {
                switch (section) {
                    case TextSection ignored -> text++;
                    case TableSection ignored -> tables++;
                    case FigureSection ignored -> figures++;
                }
            }
            return new Stats(text, tables, figures);
        }
    }
}
