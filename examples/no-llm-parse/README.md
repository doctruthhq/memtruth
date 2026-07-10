# No-LLM Parse Example

This example is the fastest way to see DocTruth's evidence anchors without an
LLM provider key. It uses only files tracked in the repository.

Build the standalone CLI jar:

```bash
mvn -f java/pom.xml package -DskipTests
```

Parse the tracked sample CSV:

```bash
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar parse examples/no-llm-parse/sample-contract.csv
```

Expected shape:

```text
examples/no-llm-parse/sample-contract.csv
pages: 1
sections: 1
text: 0
tables: 1
figures: 0
bbox coverage: 0/0
```

Write parsed JSON:

```bash
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar \
  parse examples/no-llm-parse/sample-contract.csv \
  --json \
  -o /tmp/doctruth-parsed.json
```

The JSON contains source locations and optional PDF bounding boxes for text
sections. Extraction and audit output build on these anchors.

Compile and run the Java no-LLM parser example:

```bash
mvn -f java/pom.xml package -DskipTests
mvn -q -f java/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/doctruth-example-classpath.txt
javac -cp "java/target/doctruth-java-0.2.0-alpha.jar:$(cat /tmp/doctruth-example-classpath.txt)" \
  -d target/no-llm-example \
  examples/no-llm-parse/NoLlmParse.java
java -cp "target/no-llm-example:java/target/doctruth-java-0.2.0-alpha.jar:$(cat /tmp/doctruth-example-classpath.txt)" \
  ai.doctruth.examples.nollmparse.NoLlmParse
```
