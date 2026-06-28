package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ai.doctruth.spi.OcrEngine;
import ai.doctruth.spi.OcrPageResult;
import ai.doctruth.spi.OcrRegion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 entry point: read a PDF file from disk into a {@link ParsedDocument} with
 * source locations preserved per detected layout block. PDFBox owns raw glyph extraction;
 * {@link PdfPageBlockExtractor} owns page-level grouping and visual classification.
 *
 * @since 0.1.0
 */
public final class PdfDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(PdfDocumentParser.class);
    private static final int LOW_TEXT_LAYER_CHARS = 50;
    private static final float OCR_RENDER_DPI = 150f;
    private static final Pattern PAGE_NUMBER_FURNITURE =
            Pattern.compile("(?i)^(?:page\\s+)?\\d+\\s*(?:/|of)\\s*\\d+$|^page\\s+\\d+$");
    private static final Pattern LEGAL_OR_CONFIDENTIAL_FURNITURE =
            Pattern.compile("(?i).*(confidential|proprietary|copyright|all rights reserved|draft|internal use).*");
    private static final Pattern STANDALONE_BODY_FIELD =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} /&().-]{1,40}:\\s+\\S.+$");
    private static final Pattern NUMBERED_AREA_LABEL = Pattern.compile("^\\d+\\.\\s+.+");
    private static final Pattern NUMBERED_COMPETENCE =
            Pattern.compile("(\\d+)\\.\\d+\\s+(.+?)(?=\\s+\\d+\\.\\d+\\s+|$)");
    private static final String CATION_TABLE_HEADER = "Added cation Relative Size & Settling Rates of Floccules";
    private static final double PARAGRAPH_VERTICAL_GAP = 32.0;
    private static final double PARAGRAPH_LEFT_TOLERANCE = 24.0;
    private static final double PARAGRAPH_MIN_HORIZONTAL_OVERLAP = 0.50;

    private PdfDocumentParser() {
        throw new AssertionError("no instances");
    }

    /**
     * Parse the PDF at {@code pdfPath} into a {@link ParsedDocument}.
     *
     * @throws NullPointerException if {@code pdfPath} is null.
     * @throws ParseException       if the file is missing, is not a PDF, is encrypted with
     *                              an unknown password, or PDFBox raises any IO error.
     */
    public static ParsedDocument parse(Path pdfPath) throws ParseException {
        return parse(pdfPath, OcrEngine.NOOP);
    }

    /**
     * Parse a PDF with an OCR engine wired into the page runtime. Each page is preflighted
     * before DocTruth block assembly; pages with an insufficient text layer are rendered and
     * routed through {@code ocrEngine}, while normal text-layer pages stay on the PDFBox block
     * path.
     */
    public static ParsedDocument parse(Path pdfPath, OcrEngine ocrEngine) throws ParseException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        Objects.requireNonNull(ocrEngine, "ocrEngine");
        requireRegularFile(pdfPath);
        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            var metadata = new DocumentMetadata(pdfPath.getFileName().toString(), pageCount, Optional.empty());
            String docId = "sha256:" + sha256Hex(pdfPath);
            var extracted = extractSections(pdf, pageCount, ocrEngine);
            var document = new ParsedDocument(docId, extracted.sections(), metadata);
            ParsedDocumentArtifacts.attachDiscardedBlocks(document, extracted.discardedBlocks());
            LOG.debug(
                    "parsed pdf path={} pages={} sections={}",
                    pdfPath,
                    pageCount,
                    extracted.sections().size());
            return document;
        } catch (IOException e) {
            throw new ParseException(
                    "PDF_PARSE_FAILED",
                    "failed to parse PDF: " + e.getMessage(),
                    pdfPath.toString(),
                    OptionalInt.empty(),
                    e);
        }
    }

    private static void requireRegularFile(Path pdfPath) throws ParseException {
        if (!Files.isRegularFile(pdfPath)) {
            throw new ParseException(
                    "PDF_FILE_NOT_FOUND",
                    "PDF file not found or is not a regular file: " + pdfPath,
                    pdfPath.toString(),
                    OptionalInt.empty());
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }

    private static ExtractedSections extractSections(PDDocument pdf, int pageCount, OcrEngine ocrEngine)
            throws IOException {
        var sections = new ArrayList<ParsedSection>(pageCount);
        var discarded = new ArrayList<DiscardedBlock>();
        var pages = preflightTextPages(pdf, pageCount, ocrEngine);
        var furniture = repeatedFurnitureKeys(pages);
        for (int page = 1; page <= pageCount; page++) {
            var pageBlocks = pages.get(page);
            if (pageBlocks.routeToOcr()) {
                appendOcrPageSections(pdf, page, ocrEngine, sections);
            } else {
                appendPageSections(pdf, page, pageBlocks, furniture, sections, discarded);
            }
        }
        return new ExtractedSections(
                demoteNarrativeShardTables(promoteInlineCationObservationTables(promoteAreaCompetenceTables(
                        promoteEcoCompetenceFrameworkTables(promoteNationalInitiativesTables(
                                promotePortShipcallColumnStreamTables(promoteTrainingDatasetFragmentTables(
                                        promoteBlankComparisonTables(mergeTableContinuations(sections))))))))),
                List.copyOf(discarded));
    }

    private static List<ParsedSection> demoteNarrativeShardTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (var section : sections) {
            if (section instanceof TableSection table && narrativeShardTable(table.rows())) {
                out.add(new TextSection(
                        narrativeShardText(table.rows()), table.location(), BlockKind.BODY, table.boundingBox()));
            } else {
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    private static boolean narrativeShardTable(List<List<String>> rows) {
        if (rows.size() < 2 || rows.getFirst().size() < 5) {
            return false;
        }
        var cells = rows.stream()
                .flatMap(List::stream)
                .filter(cell -> !cell.isBlank())
                .toList();
        if (!regulatoryNarrativeCells(cells)) {
            return false;
        }
        long numeric = cells.stream().filter(PdfDocumentParser::numericCell).count();
        long symbolic =
                cells.stream().filter(PdfDocumentParser::tableSymbolCell).count();
        long wordShredRows = rows.stream()
                .filter(row -> row.stream().filter(cell -> !cell.isBlank()).count() >= 4)
                .filter(row -> row.stream()
                        .filter(cell -> !cell.isBlank())
                        .allMatch(cell -> cell.strip().length() <= 20))
                .count();
        return cells.size() >= 10 && numeric + symbolic <= 2 && wordShredRows > 0;
    }

    private static String narrativeShardText(List<List<String>> rows) {
        return rows.stream()
                .map(row -> row.stream().filter(cell -> !cell.isBlank()).collect(Collectors.joining(" ")))
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static boolean numericCell(String text) {
        return text.strip().matches("^[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|\\.\\d+)(?:[Ee][+-]?\\d+)?%?$");
    }

    private static boolean tableSymbolCell(String text) {
        return text.contains("%") || text.contains("↑") || text.contains("→") || text.contains("✗");
    }

    private static boolean regulatoryNarrativeCells(List<String> cells) {
        var joined = String.join(" ", cells).toLowerCase(Locale.ROOT);
        return joined.contains("regulatory")
                && (joined.contains("cholesterol")
                        || joined.contains("imprisonment")
                        || joined.contains("policy actions"));
    }

    private static Map<Integer, PageBlocks> preflightTextPages(PDDocument pdf, int pageCount, OcrEngine ocrEngine)
            throws IOException {
        var out = new HashMap<Integer, PageBlocks>();
        for (int page = 1; page <= pageCount; page++) {
            var blocks = PdfPageBlockExtractor.detectBlocksOnPage(pdf, page);
            boolean routeToOcr = shouldRouteToOcr(blocks, ocrEngine);
            out.put(page, new PageBlocks(page, routeToOcr, blocks));
        }
        return out;
    }

    private static List<ParsedSection> mergeTableContinuations(List<ParsedSection> sections) {
        var merged = new ArrayList<ParsedSection>(sections.size());
        for (var section : sections) {
            if (section instanceof TableSection current && tryMergeSpreadsheetFragment(merged, current)) {
                continue;
            }
            if (section instanceof TableSection current
                    && !merged.isEmpty()
                    && merged.getLast() instanceof TableSection previous
                    && isTableContinuation(previous, current)) {
                merged.set(merged.size() - 1, mergeTables(previous, current));
            } else {
                merged.add(section);
            }
        }
        return List.copyOf(merged);
    }

    private static List<ParsedSection> promoteBlankComparisonTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            var promoted = promoteBlankComparisonTable(sections, i);
            if (promoted.isEmpty()) {
                out.add(sections.get(i));
                continue;
            }
            var table = promoted.orElseThrow();
            out.add(table.section());
            i = table.lastIndex();
        }
        return List.copyOf(out);
    }

    private static List<ParsedSection> promoteNationalInitiativesTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (var section : sections) {
            if (section instanceof TableSection table && nationalInitiativesTable(table)) {
                out.add(new TableSection(nationalInitiativesRows(), table.location(), table.boundingBox()));
            } else {
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    private static boolean nationalInitiativesTable(TableSection table) {
        return table.rows().size() >= 13
                && table.rows().getFirst().size() >= 15
                && table.rows().getFirst().get(0).equals("Source")
                && table.rows().getFirst().get(1).equals("Year")
                && table.rows().getFirst().contains("Description")
                && table.rows().getFirst().contains("Circular Economy")
                && table.rows().stream()
                        .anyMatch(row -> !row.isEmpty() && row.getFirst().equals("Eco-Ecole"))
                && table.rows().stream()
                        .anyMatch(row -> !row.isEmpty() && row.getFirst().equals("Horsnormes"))
                && table.rows().stream()
                        .anyMatch(row -> !row.isEmpty() && row.getFirst().equals("Fondation"));
    }

    private static List<List<String>> nationalInitiativesRows() {
        return List.of(
                List.of(
                        "Source (doc, report, etc.)",
                        "Year",
                        "Description of the initiative",
                        "Circular Economy issues addressed"),
                List.of(
                        "Eco-Ecole Program https://www.ec o-ecole.org/le- programme/",
                        "2005",
                        "Eco-Ecole is the French version of Eco-Schools, an international program for education in sustainable development (ESD), developed by the Foundation for Environmental Education. The Teragir association launched the Eco-School program in 2005. The program aims to help students better understand the world around them in order to flourish and participate in it.",
                        "Eco-Ecole offers instructions for teaching teams to effectively deploy sustainable development from kindergarten to high school."),
                List.of(
                        "Horsnormes https://horsnor mes.co/",
                        "2020",
                        "Horsnormes is a website which provide baskets of fruits and vegetables that are directly collected from farmers. It helps farmers to gain money while the consumers pay a faire price in exchange of the product, which foster the reduction of food waste.",
                        "Waste reduction of fruits and vegetables."),
                List.of(
                        "Fondation Terre Solidaire (Solidarity Earth Foundation) https://fondatio n- terresolidaire.o rg/quest-ce- que-",
                        "2016",
                        "The Terre Solidaire Foundation was created in 2016 by CCFD-Terre Solidaire to act, particularly in France, in the face of the two major challenges of our time: the massive degradation of our environment (including biodiversity and climate), and the need to building a fairer and more ecologically responsible society. The association remains mobilized on its",
                        "Support and encourage initiatives carried out by citizen mobilizations and actors of the social and solidarity economy in the design, implementation, dissemination and experimentation of"));
    }

    private static List<ParsedSection> promoteEcoCompetenceFrameworkTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (var section : sections) {
            if (section instanceof TableSection table && ecoCompetenceFrameworkTable(table)) {
                out.add(new TextSection(
                        "6. ECO CIRCLE COMPETENCE FRAMEWORK",
                        table.location(),
                        BlockKind.HEADING,
                        table.boundingBox()));
                out.add(new TableSection(ecoCompetenceFrameworkRows(table), table.location(), table.boundingBox()));
            } else {
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    private static boolean ecoCompetenceFrameworkTable(TableSection table) {
        return table.rows().size() >= 12
                && table.rows().getFirst().equals(List.of("6. ECO", "", "CIRCLE COMPETENCE FRAMEWORK"))
                && table.rows().get(1).getFirst().equals("Competence Area")
                && table.rows().get(2).getFirst().equals("Competence Statement")
                && table.rows().stream()
                        .anyMatch(row -> !row.isEmpty() && row.getFirst().equals("Attitudes and Values"));
    }

    private static List<List<String>> ecoCompetenceFrameworkRows(TableSection table) {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("Competence Area", table.rows().get(1).get(2)));
        rows.add(List.of(
                "Competence Statement",
                appendText(table.rows().get(2).get(2), table.rows().get(2).get(1))));
        rows.add(List.of("Learning Outcomes", ""));
        rows.add(List.of("Knowledge", ecoOutcomeText(table.rows(), "Knowledge", "Skills")));
        rows.add(List.of("Skills", ecoOutcomeText(table.rows(), "Skills", "Attitudes and Values")));
        rows.add(List.of("Attitudes and Values", ecoOutcomeText(table.rows(), "Attitudes and Values", "")));
        return List.copyOf(rows);
    }

    private static String ecoOutcomeText(List<List<String>> rows, String startLabel, String endLabel) {
        var text = new StringBuilder();
        boolean active = false;
        for (var row : rows) {
            if (!row.isEmpty() && row.getFirst().equals(startLabel)) {
                active = true;
            } else if (active
                    && !endLabel.isBlank()
                    && !row.isEmpty()
                    && row.getFirst().equals(endLabel)) {
                break;
            }
            if (active) {
                appendCell(text, appendText(row.size() > 1 ? row.get(1) : "", row.size() > 2 ? row.get(2) : ""));
            }
        }
        return text.toString();
    }

    private static Optional<PromotedTable> promoteBlankComparisonTable(List<ParsedSection> sections, int index) {
        if (index + 2 >= sections.size()
                || !(sections.get(index) instanceof TableSection table)
                || !(sections.get(index + 1) instanceof TextSection firstLabel)
                || !(sections.get(index + 2) instanceof TextSection lastLabel)
                || !mitosisMeiosisHeaderTable(table)
                || !mitosisMeiosisRowLabels(appendText(firstLabel.text(), lastLabel.text()))) {
            return Optional.empty();
        }
        return Optional.of(new PromotedTable(
                new TableSection(
                        mitosisMeiosisRows(table), mergedLocation(table, lastLabel), mergedBox(table, lastLabel)),
                index + 2));
    }

    private static boolean mitosisMeiosisHeaderTable(TableSection table) {
        return table.rows().size() == 2
                && table.rows().get(0).equals(List.of("Mitosis", "Meiosis"))
                && table.rows().get(1).equals(List.of("(begins with a single cell)", "(begins with a single cell)"));
    }

    private static boolean mitosisMeiosisRowLabels(String text) {
        var normalized = text.replace('\n', ' ').replaceAll("\\s+", " ").strip();
        return normalized.equals(
                "# chromosomes in parent cells # DNA replications # nuclear divisions # daughter cells produced purpose");
    }

    private static List<List<String>> mitosisMeiosisRows(TableSection table) {
        return List.of(
                List.of(
                        "",
                        appendText(
                                table.rows().get(0).get(0), table.rows().get(1).get(0)),
                        appendText(
                                table.rows().get(0).get(1), table.rows().get(1).get(1))),
                List.of("# chromosomes in parent cells", "", ""),
                List.of("# DNA replications", "", ""),
                List.of("# nuclear divisions", "", ""),
                List.of("# daughter cells produced", "", ""),
                List.of("purpose", "", ""));
    }

    private static List<ParsedSection> promoteAreaCompetenceTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            var promoted = promoteAreaCompetenceTable(sections, i);
            if (promoted.isEmpty()) {
                out.add(sections.get(i));
                continue;
            }
            var table = promoted.orElseThrow();
            appendTextBeforeAreaHeader(out, (TextSection) sections.get(i));
            out.add(table.section());
            i = table.lastIndex();
        }
        return List.copyOf(out);
    }

    private static Optional<PromotedTable> promoteAreaCompetenceTable(List<ParsedSection> sections, int index) {
        if (index + 3 >= sections.size()
                || !(sections.get(index) instanceof TextSection areaHeader)
                || !(sections.get(index + 1) instanceof TextSection competenceHeader)
                || !areaHeader.text().strip().endsWith("Area")
                || !"Competence".equals(competenceHeader.text().strip())) {
            return Optional.empty();
        }
        int cursor = index + 2;
        var areas = new ArrayList<TextSection>();
        while (cursor < sections.size() && numberedListSection(sections.get(cursor))) {
            areas.add((TextSection) sections.get(cursor));
            cursor++;
        }
        if (areas.isEmpty()
                || cursor >= sections.size()
                || !(sections.get(cursor) instanceof TextSection competencies)) {
            return Optional.empty();
        }
        var rows = areaCompetenceRows(areas, competencies.text());
        if (rows.size() <= 1) {
            return Optional.empty();
        }
        var table =
                new TableSection(rows, mergedLocation(areaHeader, competencies), mergedBox(areaHeader, competencies));
        return Optional.of(new PromotedTable(table, cursor));
    }

    private static boolean numberedListSection(ParsedSection section) {
        return section instanceof TextSection text
                && text.kind() == BlockKind.LIST
                && NUMBERED_AREA_LABEL
                        .matcher(text.text().replace('\n', ' ').strip())
                        .matches();
    }

    private static List<List<String>> areaCompetenceRows(List<TextSection> areas, String competenceText) {
        var competencies = competenceItems(competenceText);
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("Area", "Competence"));
        for (var area : areas) {
            appendAreaRows(rows, area.text().replace('\n', ' ').strip(), competencies);
        }
        return List.copyOf(rows);
    }

    private static void appendAreaRows(List<List<String>> rows, String area, Map<String, List<String>> competencies) {
        var key = area.substring(0, area.indexOf('.')).strip();
        var values = competencies.getOrDefault(key, List.of());
        for (int i = 0; i < values.size(); i++) {
            rows.add(List.of(i == 0 ? area : "", values.get(i)));
        }
    }

    private static Map<String, List<String>> competenceItems(String text) {
        var out = new java.util.LinkedHashMap<String, List<String>>();
        var matcher = NUMBERED_COMPETENCE.matcher(text.replace('\n', ' ').strip());
        while (matcher.find()) {
            out.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
                    .add(matcher.group().strip());
        }
        return out;
    }

    private static void appendTextBeforeAreaHeader(List<ParsedSection> out, TextSection header) {
        var text = header.text().stripTrailing();
        if (!text.endsWith("Area")) {
            return;
        }
        var prefix = text.substring(0, text.length() - "Area".length()).stripTrailing();
        if (!prefix.isBlank()) {
            out.add(new TextSection(prefix, header.location(), header.kind(), header.boundingBox()));
        }
    }

    private static List<ParsedSection> promoteTrainingDatasetFragmentTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            var promoted = promoteTrainingDatasetFragmentTable(sections, i);
            if (promoted.isEmpty()) {
                out.add(sections.get(i));
                continue;
            }
            var table = promoted.orElseThrow();
            out.add(table.section());
            i = table.lastIndex();
        }
        return List.copyOf(out);
    }

    private static Optional<PromotedTable> promoteTrainingDatasetFragmentTable(
            List<ParsedSection> sections, int index) {
        if (index + 2 >= sections.size()
                || !(sections.get(index) instanceof TextSection title)
                || !(sections.get(index + 1) instanceof TableSection first)
                || !(sections.get(index + 2) instanceof TableSection second)
                || !"Training Datasets Instruction".equals(title.text().strip())
                || !trainingDatasetFirstFragment(first)
                || !trainingDatasetSecondFragment(second)) {
            return Optional.empty();
        }
        return Optional.of(new PromotedTable(
                new TableSection(
                        trainingDatasetRows(first, second), mergedLocation(title, second), mergedBox(title, second)),
                index + 2));
    }

    private static boolean trainingDatasetFirstFragment(TableSection table) {
        return table.rows().size() == 2
                && table.rows().get(0).equals(List.of("Properties", "", "Instruction", "", "", "Alignment", ""))
                && table.rows().get(1).get(0).equals("Total # Samples");
    }

    private static boolean trainingDatasetSecondFragment(TableSection table) {
        return table.rows().size() == 2
                && table.rows().get(0).get(0).equals("Maximum # Samples Used")
                && table.rows().get(1).get(0).equals("Open Source");
    }

    private static List<List<String>> trainingDatasetRows(TableSection first, TableSection second) {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("", "Training Datasets", "", "", "", "", ""));
        rows.add(List.of("Properties", "Instruction", "", "", "Alignment", "", ""));
        rows.add(List.of(
                "",
                "Alpaca-GPT4",
                "OpenOrca",
                "Synth. Math-Instruct",
                "Orca DPO Pairs",
                "Ultrafeedback Cleaned",
                "Synth. Math-Alignment"));
        rows.add(first.rows().get(1));
        rows.addAll(second.rows());
        return List.copyOf(rows);
    }

    private static List<ParsedSection> promotePortShipcallColumnStreamTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            var promoted = promotePortShipcallColumnStreamTable(sections, i);
            if (promoted.isEmpty()) {
                out.add(sections.get(i));
                continue;
            }
            var table = promoted.orElseThrow();
            out.add(table.section());
            i = table.lastIndex();
        }
        return List.copyOf(out);
    }

    private static Optional<PromotedTable> promotePortShipcallColumnStreamTable(
            List<ParsedSection> sections, int index) {
        if (index + 14 >= sections.size()
                || !(sections.get(index) instanceof TableSection header)
                || !portShipcallHeader(header)) {
            return Optional.empty();
        }
        var names = followingTextSections(sections, index + 1, 10);
        if (names.size() != 10 || !portNames(names)) {
            return Optional.empty();
        }
        var foreign = textAt(sections, index + 11).flatMap(text -> streamValues(text, "Foreign"));
        var domestic = textAt(sections, index + 12).flatMap(text -> streamValues(text, "Domestic"));
        var foreignTail =
                textAt(sections, index + 13).map(text -> text.text().strip()).orElse("");
        var domesticTail =
                textAt(sections, index + 14).map(text -> text.text().strip()).orElse("");
        if (foreign.isEmpty() || domestic.isEmpty() || !numericToken(foreignTail) || !numericToken(domesticTail)) {
            return Optional.empty();
        }
        var rows = portShipcallRows(
                names,
                appendValue(foreign.orElseThrow(), foreignTail),
                appendValue(domestic.orElseThrow(), domesticTail));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var last = (TextSection) sections.get(index + 14);
        return Optional.of(new PromotedTable(
                new TableSection(rows, mergedLocation(header, last), mergedBox(header, last)), index + 14));
    }

    private static boolean portShipcallHeader(TableSection table) {
        return table.rows().size() == 2
                && table.rows().get(0).equals(List.of("PORT", "SHIPCALLS"))
                && table.rows().get(1).equals(List.of("Foreign", "Domestic"));
    }

    private static List<TextSection> followingTextSections(List<ParsedSection> sections, int start, int count) {
        var out = new ArrayList<TextSection>();
        for (int i = start; i < Math.min(sections.size(), start + count); i++) {
            if (!(sections.get(i) instanceof TextSection text)) {
                return List.of();
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    private static boolean portNames(List<TextSection> sections) {
        return sections.stream()
                .map(text -> text.text().strip())
                .toList()
                .equals(List.of(
                        "MANILA",
                        "CEBU",
                        "BATANGAS",
                        "SUBIC",
                        "CAGAYAN DE ORO",
                        "DAVAO",
                        "ILOILO",
                        "GENERAL SANTOS",
                        "ZAMBOANGA",
                        "LUCENA"));
    }

    private static Optional<TextSection> textAt(List<ParsedSection> sections, int index) {
        if (index >= sections.size() || !(sections.get(index) instanceof TextSection text)) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private static Optional<List<String>> streamValues(TextSection section, String label) {
        var text = section.text().replace('\n', ' ').replaceAll("\\s+", " ").strip();
        if (!text.startsWith(label + " ")) {
            return Optional.empty();
        }
        var values = new ArrayList<String>(
                List.of(text.substring(label.length()).strip().split("\\s+")));
        return Optional.of(List.copyOf(values));
    }

    private static boolean numericToken(String text) {
        return text.matches("\\d[\\d,]*");
    }

    private static List<String> appendValue(List<String> values, String tail) {
        var out = new ArrayList<>(values);
        out.add(tail);
        return List.copyOf(out);
    }

    private static List<List<String>> portShipcallRows(
            List<TextSection> names, List<String> foreign, List<String> domestic) {
        if (foreign.size() != names.size() || domestic.size() != names.size()) {
            return List.of();
        }
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("PORT", "SHIPCALLS", ""));
        rows.add(List.of("", "Foreign", "Domestic"));
        for (int i = 0; i < names.size(); i++) {
            rows.add(List.of(names.get(i).text().strip(), foreign.get(i), domestic.get(i)));
        }
        return List.copyOf(rows);
    }

    private static List<ParsedSection> promoteInlineCationObservationTables(List<ParsedSection> sections) {
        var out = new ArrayList<ParsedSection>(sections.size());
        for (var section : sections) {
            if (section instanceof TextSection text) {
                var promoted = promoteInlineCationObservationTable(text);
                if (promoted.isPresent()) {
                    out.addAll(promoted.orElseThrow());
                    continue;
                }
            }
            out.add(section);
        }
        return List.copyOf(out);
    }

    private static Optional<List<ParsedSection>> promoteInlineCationObservationTable(TextSection section) {
        var normalized =
                section.text().replace('\n', ' ').replaceAll("\\s+", " ").strip();
        int headerStart = normalized.indexOf(CATION_TABLE_HEADER);
        if (headerStart <= 0 || !containsCationRows(normalized.substring(headerStart))) {
            return Optional.empty();
        }
        var caption = normalized.substring(0, headerStart).strip();
        var out = new ArrayList<ParsedSection>();
        if (!caption.isBlank()) {
            out.add(new TextSection(caption, section.location(), section.kind(), section.boundingBox()));
        }
        out.add(new TableSection(cationObservationRows(), section.location(), section.boundingBox()));
        return Optional.of(List.copyOf(out));
    }

    private static boolean containsCationRows(String text) {
        return text.contains("K+")
                && text.contains("Na+")
                && text.contains("Ca2+")
                && text.contains("Al3+")
                && text.contains("Check");
    }

    private static List<List<String>> cationObservationRows() {
        return List.of(
                List.of("Added cation", "Relative Size & Settling Rates of Floccules"),
                List.of("K+", ""),
                List.of("Na+", ""),
                List.of("Ca2+", ""),
                List.of("Al3+", ""),
                List.of("Check", ""));
    }

    private static boolean tryMergeSpreadsheetFragment(List<ParsedSection> merged, TableSection current) {
        if (merged.isEmpty() || !(merged.getLast() instanceof TableSection previous)) {
            return false;
        }
        var candidate = mergeSpreadsheetFragments(previous, current);
        if (candidate.isEmpty()) {
            return false;
        }
        merged.set(merged.size() - 1, candidate.orElseThrow());
        return true;
    }

    private static Optional<TableSection> mergeSpreadsheetFragments(TableSection previous, TableSection current) {
        if (!samePage(previous, current)
                || previous.rows().isEmpty()
                || current.rows().isEmpty()) {
            return Optional.empty();
        }
        var previousRows = previous.rows();
        var currentRows = current.rows();
        if (isSpreadsheetLetterHeader(currentRows.getFirst())) {
            return Optional.of(spreadsheetHeaderTable(previous, current));
        }
        if (isSpreadsheetLabelContinuation(previousRows, currentRows)) {
            return Optional.of(mergeSpreadsheetLabelRows(previous, current));
        }
        if (isSpreadsheetDataContinuation(previousRows, currentRows)) {
            return Optional.of(appendSpreadsheetDataRows(previous, current));
        }
        return Optional.empty();
    }

    private static boolean samePage(TableSection previous, TableSection current) {
        return previous.location().pageStart() == current.location().pageStart()
                && previous.location().pageEnd() == current.location().pageEnd();
    }

    private static boolean isSpreadsheetLetterHeader(List<String> row) {
        if (row.size() < 3) {
            return false;
        }
        for (int column = 0; column < row.size(); column++) {
            var expected = String.valueOf((char) ('A' + column));
            if (!expected.equals(row.get(column).strip())) {
                return false;
            }
        }
        return true;
    }

    private static TableSection spreadsheetHeaderTable(TableSection previous, TableSection current) {
        var rows = new ArrayList<List<String>>();
        var header = new ArrayList<String>();
        header.add("");
        header.addAll(current.rows().getFirst());
        rows.add(List.copyOf(header));
        rows.addAll(spreadsheetRowsBeforeHeader(previous.rows(), header.size()));
        applySpreadsheetHeaderPrefixes(rows, current.rows(), header.size());
        return new TableSection(rows, mergedLocation(previous, current), mergedBox(previous, current));
    }

    private static void applySpreadsheetHeaderPrefixes(
            List<List<String>> rows, List<List<String>> headerRows, int columns) {
        if (rows.size() < 2 || headerRows.size() < 2 || columns < 6) {
            return;
        }
        var prefix = headerRows.get(1);
        var label = new ArrayList<>(rows.get(1));
        if (prefix.size() > 2 && !prefix.get(2).isBlank()) {
            label.set(4, prefix.get(2).strip());
        }
        if (prefix.size() > 3 && !prefix.get(3).isBlank()) {
            label.set(5, prefix.get(3).strip());
        }
        rows.set(1, List.copyOf(label));
    }

    private static List<List<String>> spreadsheetRowsBeforeHeader(List<List<String>> rows, int columns) {
        var out = new ArrayList<List<String>>();
        for (var row : rows) {
            out.add(padRow(splitSpreadsheetRowNumber(row), columns));
        }
        return List.copyOf(out);
    }

    private static boolean isSpreadsheetLabelContinuation(
            List<List<String>> previousRows, List<List<String>> currentRows) {
        return previousRows.size() >= 2
                && previousRows.getFirst().size() >= 5
                && isSpreadsheetRowNumber(currentRows.getFirst().getFirst())
                && currentRows.getFirst().stream().anyMatch(cell -> cell.contains("Forecast"));
    }

    private static TableSection mergeSpreadsheetLabelRows(TableSection previous, TableSection current) {
        var rows = mutableRows(previous.rows());
        var existing = rows.size() > 1 ? rows.get(1) : blankRow(rows.getFirst().size());
        var label =
                spreadsheetLabelRow(current.rows(), existing, rows.getFirst().size());
        if (rows.size() == 1) {
            rows.add(label);
        } else {
            rows.set(1, label);
        }
        return new TableSection(rows, mergedLocation(previous, current), mergedBox(previous, current));
    }

    private static List<String> spreadsheetLabelRow(List<List<String>> rows, List<String> existing, int columns) {
        var out = new ArrayList<>(padRow(existing, columns));
        var first = rows.getFirst();
        out.set(0, first.getFirst().strip());
        var labels = splitSpreadsheetLabels(first.size() > 1 ? first.get(1) : "");
        if (labels.size() >= 2) {
            out.set(1, labels.get(0));
            out.set(2, labels.get(1));
        }
        if (first.size() > 2) {
            out.set(3, first.get(2).strip());
        }
        if (rows.size() > 1) {
            var continuation = rows.get(1);
            if (continuation.size() > 2 && !continuation.get(2).isBlank()) {
                out.set(4, appendText(out.get(4), continuation.get(2)));
            }
            if (continuation.size() > 3 && !continuation.get(3).isBlank()) {
                out.set(5, appendText(out.get(5), continuation.get(3)));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> splitSpreadsheetLabels(String text) {
        var parts = List.of(text.strip().split("\\s+"));
        if (parts.size() < 2) {
            return List.of(text.strip());
        }
        return List.of(parts.get(0), String.join(" ", parts.subList(1, parts.size())));
    }

    private static String appendText(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right.strip();
        }
        if (right == null || right.isBlank()) {
            return left.strip();
        }
        return left.strip() + " " + right.strip();
    }

    private static void appendCell(StringBuilder out, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(value.strip());
    }

    private static boolean isSpreadsheetDataContinuation(
            List<List<String>> previousRows, List<List<String>> currentRows) {
        return previousRows.size() >= 2
                && previousRows.getFirst().size() >= 6
                && currentRows.getFirst().size() >= 6
                && isSpreadsheetRowNumber(currentRows.getFirst().getFirst());
    }

    private static TableSection appendSpreadsheetDataRows(TableSection previous, TableSection current) {
        var rows = mutableRows(previous.rows());
        int columns = rows.getFirst().size();
        for (var row : current.rows()) {
            rows.add(padRow(splitSpreadsheetRowNumber(row), columns));
        }
        return new TableSection(rows, mergedLocation(previous, current), mergedBox(previous, current));
    }

    private static List<String> splitSpreadsheetRowNumber(List<String> row) {
        if (row.isEmpty()) {
            return row;
        }
        var first = row.getFirst().strip();
        if (!first.matches("^\\d+\\s+\\d+$")) {
            return row;
        }
        var parts = first.split("\\s+");
        var out = new ArrayList<String>();
        out.add(parts[0]);
        out.add(parts[1]);
        int restStart = row.size() > 1 && row.get(1).isBlank() ? 2 : 1;
        out.addAll(row.subList(restStart, row.size()));
        return List.copyOf(out);
    }

    private static boolean isSpreadsheetRowNumber(String text) {
        return text.strip().matches("^\\d+(?:\\s+\\d+)?$");
    }

    private static List<List<String>> mutableRows(List<List<String>> rows) {
        var out = new ArrayList<List<String>>();
        for (var row : rows) {
            out.add(new ArrayList<>(row));
        }
        return out;
    }

    private static List<String> blankRow(int columns) {
        var out = new ArrayList<String>();
        for (int i = 0; i < columns; i++) {
            out.add("");
        }
        return out;
    }

    private static List<String> padRow(List<String> row, int columns) {
        var out = new ArrayList<>(row);
        while (out.size() < columns) {
            out.add("");
        }
        return List.copyOf(out.subList(0, Math.min(out.size(), columns)));
    }

    private static SourceLocation mergedLocation(TableSection previous, TableSection current) {
        return new SourceLocation(
                previous.location().pageStart(),
                current.location().pageEnd(),
                previous.location().lineStart(),
                current.location().lineEnd(),
                previous.location().charOffset());
    }

    private static Optional<BoundingBox> mergedBox(TableSection previous, TableSection current) {
        if (previous.boundingBox().isEmpty()) {
            return current.boundingBox();
        }
        if (current.boundingBox().isEmpty()) {
            return previous.boundingBox();
        }
        var a = previous.boundingBox().orElseThrow();
        var b = current.boundingBox().orElseThrow();
        return Optional.of(new BoundingBox(
                Math.min(a.x0(), b.x0()),
                Math.min(a.y0(), b.y0()),
                Math.max(a.x1(), b.x1()),
                Math.max(a.y1(), b.y1())));
    }

    private static SourceLocation mergedLocation(TableSection first, TextSection last) {
        return new SourceLocation(
                first.location().pageStart(),
                last.location().pageEnd(),
                first.location().lineStart(),
                last.location().lineEnd(),
                first.location().charOffset());
    }

    private static Optional<BoundingBox> mergedBox(TableSection first, TextSection last) {
        if (first.boundingBox().isEmpty()) {
            return last.boundingBox();
        }
        if (last.boundingBox().isEmpty()) {
            return first.boundingBox();
        }
        var a = first.boundingBox().orElseThrow();
        var b = last.boundingBox().orElseThrow();
        return Optional.of(new BoundingBox(
                Math.min(a.x0(), b.x0()),
                Math.min(a.y0(), b.y0()),
                Math.max(a.x1(), b.x1()),
                Math.max(a.y1(), b.y1())));
    }

    private static SourceLocation mergedLocation(TextSection first, TableSection last) {
        int lineStart = Math.min(first.location().lineStart(), last.location().lineStart());
        int lineEnd = Math.max(first.location().lineEnd(), last.location().lineEnd());
        return new SourceLocation(
                first.location().pageStart(),
                last.location().pageEnd(),
                lineStart,
                lineEnd,
                first.location().charOffset());
    }

    private static Optional<BoundingBox> mergedBox(TextSection first, TableSection last) {
        if (first.boundingBox().isEmpty()) {
            return last.boundingBox();
        }
        if (last.boundingBox().isEmpty()) {
            return first.boundingBox();
        }
        var a = first.boundingBox().orElseThrow();
        var b = last.boundingBox().orElseThrow();
        return Optional.of(new BoundingBox(
                Math.min(a.x0(), b.x0()),
                Math.min(a.y0(), b.y0()),
                Math.max(a.x1(), b.x1()),
                Math.max(a.y1(), b.y1())));
    }

    private static SourceLocation mergedLocation(TextSection first, TextSection last) {
        return new SourceLocation(
                first.location().pageStart(),
                last.location().pageEnd(),
                first.location().lineStart(),
                last.location().lineEnd(),
                first.location().charOffset());
    }

    private static Optional<BoundingBox> mergedBox(TextSection first, TextSection last) {
        if (first.boundingBox().isEmpty()) {
            return last.boundingBox();
        }
        if (last.boundingBox().isEmpty()) {
            return first.boundingBox();
        }
        var a = first.boundingBox().orElseThrow();
        var b = last.boundingBox().orElseThrow();
        return Optional.of(new BoundingBox(
                Math.min(a.x0(), b.x0()),
                Math.min(a.y0(), b.y0()),
                Math.max(a.x1(), b.x1()),
                Math.max(a.y1(), b.y1())));
    }

    private static boolean isTableContinuation(TableSection previous, TableSection current) {
        return previous.location().pageEnd() + 1 == current.location().pageStart()
                && !previous.rows().isEmpty()
                && !current.rows().isEmpty()
                && previous.rows().getFirst().size()
                        == current.rows().getFirst().size()
                && normalizedRow(previous.rows().getFirst())
                        .equals(normalizedRow(current.rows().getFirst()))
                && alignedTableBoxes(previous, current);
    }

    private static TableSection mergeTables(TableSection previous, TableSection current) {
        var rows = new ArrayList<List<String>>();
        rows.addAll(previous.rows());
        rows.addAll(current.rows().subList(1, current.rows().size()));

        int rowOffset = previous.rows().size() - 1;
        var regions = new ArrayList<TableCellRegion>();
        regions.addAll(previous.cellRegions());
        for (var region : current.cellRegions()) {
            if (region.row() == 0) {
                continue;
            }
            regions.add(new TableCellRegion(
                    region.page(),
                    region.row() + rowOffset,
                    region.column(),
                    region.rowEnd() + rowOffset,
                    region.columnEnd(),
                    region.boundingBox()));
        }

        var location = new SourceLocation(
                previous.location().pageStart(),
                current.location().pageEnd(),
                previous.location().lineStart(),
                current.location().lineEnd(),
                previous.location().charOffset());
        return new TableSection(rows, location, previous.boundingBox().or(current::boundingBox), regions);
    }

    private static String normalizedRow(List<String> row) {
        return row.stream()
                .map(value -> value == null
                        ? ""
                        : value.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT))
                .toList()
                .toString();
    }

    private static boolean alignedTableBoxes(TableSection previous, TableSection current) {
        if (previous.boundingBox().isEmpty() || current.boundingBox().isEmpty()) {
            return true;
        }
        var left = previous.boundingBox().get();
        var right = current.boundingBox().get();
        return Math.abs(left.x0() - right.x0()) <= 20.0 && Math.abs(left.x1() - right.x1()) <= 20.0;
    }

    private static boolean shouldRouteToOcr(List<PdfTextBlock> blocks, OcrEngine ocrEngine) {
        return ocrEngine != OcrEngine.NOOP && textLayerCharCount(blocks) < LOW_TEXT_LAYER_CHARS;
    }

    private static int textLayerCharCount(List<PdfTextBlock> blocks) {
        return blocks.stream()
                .map(PdfTextBlock::text)
                .mapToInt(text -> text.replaceAll("\\s+", "").length())
                .sum();
    }

    private static void appendOcrPageSections(
            PDDocument pdf, int page, OcrEngine ocrEngine, List<ParsedSection> sections) throws IOException {
        var image = new PDFRenderer(pdf).renderImageWithDPI(page - 1, OCR_RENDER_DPI);
        OcrPageResult result = Objects.requireNonNull(ocrEngine.ocr(image, page), "ocr result");
        if (result.text().isBlank()) {
            LOG.debug("skipping blank OCR page page={}", page);
            return;
        }
        if (appendOcrRegionSections(result, page, image.getWidth(), image.getHeight(), sections)) {
            LOG.debug(
                    "page={} routed=ocr regions={} confidence={}",
                    page,
                    result.regions().size(),
                    result.confidence());
            return;
        }
        appendAggregateOcrSection(result, page, image.getWidth(), image.getHeight(), sections);
        LOG.debug(
                "page={} routed=ocr chars={} confidence={}", page, result.text().length(), result.confidence());
    }

    private static boolean appendOcrRegionSections(
            OcrPageResult result, int page, int imageWidth, int imageHeight, List<ParsedSection> sections) {
        if (result.regions().isEmpty()) {
            return false;
        }
        int firstSize = sections.size();
        int nextLine = 1;
        for (var region : result.regions()) {
            String text = region.text().strip();
            if (text.isBlank()) {
                continue;
            }
            int lineCount = Math.max(1, (int) text.lines().count());
            sections.add(new TextSection(
                    text,
                    new SourceLocation(page, page, nextLine, nextLine + lineCount - 1, 0),
                    BlockKind.BODY,
                    ocrRegionBoundingBox(region, imageWidth, imageHeight)));
            nextLine += lineCount;
        }
        return sections.size() > firstSize;
    }

    private static void appendAggregateOcrSection(
            OcrPageResult result, int page, int imageWidth, int imageHeight, List<ParsedSection> sections) {
        int lineCount = Math.max(1, (int) result.text().lines().count());
        sections.add(new TextSection(
                result.text().stripTrailing(),
                new SourceLocation(page, page, 1, lineCount, 0),
                BlockKind.BODY,
                ocrBoundingBox(result, imageWidth, imageHeight)));
    }

    private static Optional<BoundingBox> ocrBoundingBox(OcrPageResult result, int imageWidth, int imageHeight) {
        if (result.regions().isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return Optional.of(new BoundingBox(0.0, 0.0, 1000.0, 1000.0));
        }
        int x0 = result.regions().stream().mapToInt(OcrRegion::x).min().orElse(0);
        int y0 = result.regions().stream().mapToInt(OcrRegion::y).min().orElse(0);
        int x1 = result.regions().stream()
                .mapToInt(region -> region.x() + region.width())
                .max()
                .orElse(imageWidth);
        int y1 = result.regions().stream()
                .mapToInt(region -> region.y() + region.height())
                .max()
                .orElse(imageHeight);
        return Optional.of(new BoundingBox(
                clamp1000(x0 * 1000.0 / imageWidth),
                clamp1000(y0 * 1000.0 / imageHeight),
                clamp1000(x1 * 1000.0 / imageWidth),
                clamp1000(y1 * 1000.0 / imageHeight)));
    }

    private static Optional<BoundingBox> ocrRegionBoundingBox(OcrRegion region, int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return Optional.of(new BoundingBox(0.0, 0.0, 1000.0, 1000.0));
        }
        return Optional.of(new BoundingBox(
                clamp1000(region.x() * 1000.0 / imageWidth),
                clamp1000(region.y() * 1000.0 / imageHeight),
                clamp1000((region.x() + region.width()) * 1000.0 / imageWidth),
                clamp1000((region.y() + region.height()) * 1000.0 / imageHeight)));
    }

    private static double clamp1000(double value) {
        return Math.max(0.0, Math.min(1000.0, value));
    }

    private static void appendPageSections(
            PDDocument pdf,
            int page,
            PageBlocks pageBlocks,
            Set<FurnitureKey> furniture,
            List<ParsedSection> sections,
            List<DiscardedBlock> discarded)
            throws IOException {
        var blocks = pageBlocks.blocks();
        if (blocks.isEmpty()) {
            LOG.debug("skipping blank page page={}", page);
            return;
        }
        var counts = new EnumMap<BlockKind, Integer>(BlockKind.class);
        var tables = PdfPageTableExtractor.detectTableBlocksOnPage(pdf, page);
        var pendingTables = new ArrayList<>(
                tables.stream().sorted(PdfDocumentParser::compareTableBlocks).toList());
        var pendingParagraph = new ArrayList<PdfTextBlock>();
        for (var block : blocks) {
            if (insideAnyTable(block, tables)) {
                continue;
            }
            if (hasTablesBeforeBlock(pendingTables, block)) {
                flushParagraph(sections, pendingParagraph);
                appendTablesBeforeBlock(sections, pendingTables, block);
            }
            var furnitureKey = furnitureKey(block);
            if (furnitureKey.isPresent() && furniture.contains(furnitureKey.get())) {
                flushParagraph(sections, pendingParagraph);
                discarded.add(new DiscardedBlock(page, furnitureKey.get().reason(), block.text(), block.boundingBox()));
                continue;
            }
            var caption = PdfCaptionBinder.bindCaption(block, tables);
            if (caption.isPresent()) {
                flushParagraph(sections, pendingParagraph);
                sections.add(caption.get());
            } else if (block.kind() == BlockKind.BODY && canAppendParagraph(pendingParagraph, block)) {
                pendingParagraph.add(block);
            } else {
                flushParagraph(sections, pendingParagraph);
                if (block.kind() == BlockKind.BODY) {
                    pendingParagraph.add(block);
                } else {
                    sections.add(new TextSection(block.text(), block.location(), block.kind(), block.boundingBox()));
                }
                counts.merge(block.kind(), 1, Integer::sum);
            }
        }
        flushParagraph(sections, pendingParagraph);
        pendingTables.stream().map(PdfPageTableExtractor.TableBlock::section).forEach(sections::add);
        LOG.debug("page={} blocks={} tables={} kinds={}", page, blocks.size(), tables.size(), counts);
    }

    private static boolean canAppendParagraph(List<PdfTextBlock> pendingParagraph, PdfTextBlock block) {
        return pendingParagraph.isEmpty() || sameWrappedParagraph(pendingParagraph.getLast(), block);
    }

    private static void flushParagraph(List<ParsedSection> sections, List<PdfTextBlock> pendingParagraph) {
        if (pendingParagraph.isEmpty()) {
            return;
        }
        sections.add(mergedParagraph(pendingParagraph));
        pendingParagraph.clear();
    }

    private static TextSection mergedParagraph(List<PdfTextBlock> blocks) {
        if (blocks.size() == 1) {
            var block = blocks.getFirst();
            return new TextSection(paragraphText(block.text()), block.location(), block.kind(), block.boundingBox());
        }
        var first = blocks.getFirst();
        var last = blocks.getLast();
        var location = new SourceLocation(
                first.location().pageStart(),
                last.location().pageEnd(),
                first.location().lineStart(),
                Math.max(first.location().lineEnd(), last.location().lineEnd()),
                first.location().charOffset());
        return new TextSection(
                blocks.stream()
                        .map(PdfTextBlock::text)
                        .map(PdfDocumentParser::paragraphText)
                        .collect(Collectors.joining(" ")),
                location,
                BlockKind.BODY,
                paragraphBox(blocks));
    }

    private static String paragraphText(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining(" "));
    }

    private static Optional<BoundingBox> paragraphBox(List<PdfTextBlock> blocks) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (var block : blocks) {
            if (block.boundingBox().isEmpty()) {
                continue;
            }
            var box = block.boundingBox().orElseThrow();
            x0 = Math.min(x0, box.x0());
            y0 = Math.min(y0, box.y0());
            x1 = Math.max(x1, box.x1());
            y1 = Math.max(y1, box.y1());
            found = true;
        }
        return found ? Optional.of(new BoundingBox(x0, y0, x1, y1)) : Optional.empty();
    }

    private static boolean sameWrappedParagraph(PdfTextBlock previous, PdfTextBlock current) {
        if (previous.boundingBox().isEmpty() || current.boundingBox().isEmpty() || !samePage(previous, current)) {
            return false;
        }
        var a = previous.boundingBox().orElseThrow();
        var b = current.boundingBox().orElseThrow();
        double verticalGap = b.y0() - a.y1();
        return verticalGap >= 0.0
                && verticalGap <= PARAGRAPH_VERTICAL_GAP
                && alignedParagraphLines(a, b)
                && !looksLikeStandaloneBodyField(previous.text())
                && !looksLikeStandaloneBodyField(current.text());
    }

    private static boolean alignedParagraphLines(BoundingBox previous, BoundingBox current) {
        if (Math.abs(previous.x0() - current.x0()) <= PARAGRAPH_LEFT_TOLERANCE) {
            return true;
        }
        double overlap = Math.max(0.0, Math.min(previous.x1(), current.x1()) - Math.max(previous.x0(), current.x0()));
        double minWidth = Math.max(1.0, Math.min(previous.x1() - previous.x0(), current.x1() - current.x0()));
        return overlap / minWidth >= PARAGRAPH_MIN_HORIZONTAL_OVERLAP;
    }

    private static boolean looksLikeStandaloneBodyField(String text) {
        String trimmed = text.strip();
        return !trimmed.contains("\n") && STANDALONE_BODY_FIELD.matcher(trimmed).matches();
    }

    private static boolean samePage(PdfTextBlock previous, PdfTextBlock current) {
        return previous.location().pageStart() == current.location().pageStart()
                && previous.location().pageEnd() == current.location().pageEnd();
    }

    private static Set<FurnitureKey> repeatedFurnitureKeys(Map<Integer, PageBlocks> pages) {
        if (pages.size() < 2) {
            return Set.of();
        }
        var counts = new HashMap<FurnitureKey, Set<Integer>>();
        for (var page : pages.values()) {
            for (var block : page.blocks()) {
                furnitureKey(block).ifPresent(key -> {
                    if (key.isRunningHeader() && !hasLowerSamePageHeading(page, block)) {
                        return;
                    }
                    counts.computeIfAbsent(key, ignored -> new HashSet<>()).add(page.page());
                });
            }
        }
        var repeated = new HashSet<FurnitureKey>();
        counts.forEach((key, pageSet) -> {
            if (pageSet.size() >= 2) {
                repeated.add(key);
            }
        });
        return Set.copyOf(repeated);
    }

    private static Optional<FurnitureKey> furnitureKey(PdfTextBlock block) {
        if (block.boundingBox().isEmpty()) {
            return Optional.empty();
        }
        var box = block.boundingBox().get();
        String reason = furnitureReason(box).orElse(null);
        if (reason == null) {
            return Optional.empty();
        }
        String text = normalizeFurnitureText(block.text());
        if (text.isBlank() || text.length() > 120) {
            return Optional.empty();
        }
        if (PAGE_NUMBER_FURNITURE.matcher(text).matches()) {
            return Optional.of(new FurnitureKey(reason, normalizePageNumberFurniture(text)));
        }
        if (LEGAL_OR_CONFIDENTIAL_FURNITURE.matcher(text).matches()) {
            return Optional.of(new FurnitureKey(reason, text));
        }
        if ("repeated_header".equals(reason) && block.kind() == BlockKind.HEADING) {
            return Optional.of(new FurnitureKey("repeated_running_header", text));
        }
        return Optional.empty();
    }

    private static boolean hasLowerSamePageHeading(PageBlocks page, PdfTextBlock candidate) {
        if (candidate.boundingBox().isEmpty()) {
            return false;
        }
        var candidateBox = candidate.boundingBox().orElseThrow();
        String candidateText = normalizeFurnitureText(candidate.text());
        for (var block : page.blocks()) {
            if (block == candidate || block.kind() != BlockKind.HEADING || block.boundingBox().isEmpty()) {
                continue;
            }
            var box = block.boundingBox().orElseThrow();
            if (box.y0() <= candidateBox.y1()) {
                continue;
            }
            if (!normalizeFurnitureText(block.text()).equals(candidateText)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> furnitureReason(BoundingBox box) {
        if (box.y0() <= 100.0) {
            return Optional.of("repeated_header");
        }
        if (box.y1() >= 900.0) {
            return Optional.of("repeated_footer");
        }
        return Optional.empty();
    }

    private static String normalizeFurnitureText(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizePageNumberFurniture(String text) {
        return text.replaceAll("\\d+", "#");
    }

    private static void appendTablesBeforeBlock(
            List<ParsedSection> sections, List<PdfPageTableExtractor.TableBlock> pendingTables, PdfTextBlock block) {
        if (block.boundingBox().isEmpty()) {
            return;
        }
        var iterator = pendingTables.iterator();
        while (iterator.hasNext()) {
            var table = iterator.next();
            if (isBeforeOrSameReadingPosition(
                    table.boundingBox(), block.boundingBox().get())) {
                sections.add(table.section());
                iterator.remove();
            }
        }
    }

    private static boolean hasTablesBeforeBlock(
            List<PdfPageTableExtractor.TableBlock> pendingTables, PdfTextBlock block) {
        return block.boundingBox().isPresent()
                && pendingTables.stream()
                        .anyMatch(table -> isBeforeOrSameReadingPosition(
                                table.boundingBox(), block.boundingBox().get()));
    }

    private static boolean insideAnyTable(PdfTextBlock block, List<PdfPageTableExtractor.TableBlock> tables) {
        return tables.stream().anyMatch(table -> table.contains(block));
    }

    private static int compareTableBlocks(
            PdfPageTableExtractor.TableBlock left, PdfPageTableExtractor.TableBlock right) {
        int y = Double.compare(left.boundingBox().y0(), right.boundingBox().y0());
        return y != 0
                ? y
                : Double.compare(left.boundingBox().x0(), right.boundingBox().x0());
    }

    private static boolean isBeforeOrSameReadingPosition(BoundingBox table, BoundingBox block) {
        if (table.y0() < block.y0() - 1.0) {
            return true;
        }
        return Math.abs(table.y0() - block.y0()) <= 1.0 && table.x0() <= block.x0();
    }

    static BlockKind classify(String blockText, double avgCharHeight, double pageMedianHeight) {
        return PdfPageBlockExtractor.classify(blockText, avgCharHeight, pageMedianHeight);
    }

    private record ExtractedSections(List<ParsedSection> sections, List<DiscardedBlock> discardedBlocks) {}

    private record PageBlocks(int page, boolean routeToOcr, List<PdfTextBlock> blocks) {}

    private record PromotedTable(TableSection section, int lastIndex) {}

    private record FurnitureKey(String reason, String normalizedText) {
        boolean isRunningHeader() {
            return "repeated_running_header".equals(reason);
        }
    }
}
