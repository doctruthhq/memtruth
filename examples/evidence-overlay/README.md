# DocTruth evidence overlay

Visual companion to `result.toAuditJson()`. Given a PDF and an `ExtractionResult`,
this example renders one PNG per cited page with every `Citation` drawn as a
coloured highlight box on the actual rendered PDF page — so you can SEE which
span of source text justifies each extracted field, not just read it in JSON.

## What this does

1. Picks a real fixture PDF whose first page has substantial text (skips
   likely-scanned PDFs and ones whose page-1 lacks ASCII patterns to overlay).
2. Hand-builds a small `ExtractionResult<DemoExtraction>` whose `Citation`s
   point at three distinctive substrings of the page text (a headline-ish
   line, an `email@domain` match, and an ALL-CAPS run of words).
3. Renders the page at 150 DPI via PDFBox, captures every `TextPosition` for
   that page, and walks the position list to map `(charOffset → bounding box)`
   for each citation.
4. Draws a translucent fill + solid border per citation, deterministic per-field
   colour (HSL hue from `fieldPath.hashCode()`), a label above the box (or
   inside it if the box is near the top of the page), and a legend footer
   listing every field on the page with its colour swatch.
5. Writes `output/page-{N}.png` per cited page and prints the file paths.

## Run it

You need the lib jar built first:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
mvn -B -ntp package -DskipTests
```

Then compile + run the standalone example (no Maven submodule):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
CP="target/doctruth-java-0.2.0-alpha.jar:$(cat /tmp/cp.txt)"
javac -cp "$CP" -d /tmp/overlay-build examples/evidence-overlay/EvidenceOverlay.java
java  -cp "/tmp/overlay-build:$CP" ai.doctruth.examples.evidenceoverlay.EvidenceOverlay
```

## What you should see

`examples/evidence-overlay/output/page-1.png` — the rendered PDF page with three
overlaid highlight boxes: the email address in one colour, the headline in
another, and the all-caps block in a third. Each box has a small label
(`fieldName (matchScore)`) and the bottom of the page carries a legend strip
with the colour-swatch ↔ field-name mapping.

Sample stdout:

```
Rendered 1 evidence overlay(s) for fixtures/pdf/004b85e6-b2dd-4d7f-aebc-5e0bab06e1f3.pdf:
  examples/evidence-overlay/output/page-1.png
    [firstParagraphHeadline   ] "Nama Penuh: Amy Bin Muhammad" matchScore=1.00 -> page 1
    [emailAddress             ] "banjaramy@yahoo.com"          matchScore=1.00 -> page 1
    [allCapsBlock             ] "MAKLUMAT PERIBADI"            matchScore=1.00 -> page 1
```

## Adapt to your own data

Swap the demo `DemoExtraction` record + hand-crafted `Citation`s for an actual
`DocTruth.from(provider).extract(...).run(doc)` call. The `render(pdfPath,
result, outputDir)` method takes any `ExtractionResult<?>` — its citations map
keys become field labels, and each citation's `exactQuote` is matched against
the page's `TextPosition` list to derive the bounding box. Citations whose
quote does not appear verbatim on the page emit a SLF4J warning and are skipped
(match-failure is auditable, not silent).
