# No-LLM Parse Example

This example is the fastest way to see DocTruth's evidence anchors without an
LLM provider key.

Build the standalone CLI jar:

```bash
mvn package -DskipTests
```

Parse a PDF:

```bash
java -jar target/doctruth-java-0.2.0-alpha-all.jar parse fixtures/pdf/ResumeAFIQDANISH.pdf --bboxes
```

Expected shape:

```text
fixtures/pdf/ResumeAFIQDANISH.pdf
pages: 1
sections: 12
text: 12
tables: 0
figures: 0
bbox coverage: 12/12
```

Write parsed JSON:

```bash
java -jar target/doctruth-java-0.2.0-alpha-all.jar \
  parse fixtures/pdf/ResumeAFIQDANISH.pdf \
  --json \
  -o /tmp/doctruth-parsed.json
```

The JSON contains source locations and optional PDF bounding boxes for text
sections. Extraction and audit output build on these anchors.
