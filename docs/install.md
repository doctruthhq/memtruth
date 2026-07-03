# Install DocTruth CLI

The Java SDK is the primary production integration path. The CLI is the
try/debug/inspect path: it lets a Java team verify the core promise before
writing integration code:

```text
document -> parsed sections with source locations -> schema check -> audit output
```

## SDK Install

Use the SDK when adding DocTruth to an application:

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.2.0-alpha</version>
</dependency>
```

Minimal application flow:

```java
var result = DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract contract terms", Contract.class)
        .withEvidence()
        .run();
```

## CLI From Source

Requires Java 25+ and Maven.

Build the standalone jar:

```bash
mvn package -DskipTests
```

Run it directly:

```bash
java -jar target/doctruth-java-0.2.0-alpha-all.jar --help
```

Install a `doctruth` launcher:

```bash
scripts/install-cli.sh --prefix "$HOME/.local"
```

Make sure the install prefix is on your path:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Check the install:

```bash
doctruth version
doctruth doctor
doctruth parse examples/no-llm-parse/sample-contract.csv
```

If `java` is not on `PATH`, point the launcher at your Java 25 runtime:

```bash
JAVA=/path/to/java doctruth version
```

On macOS, `/usr/bin/java` may be a stub even when Maven can find a Homebrew JDK.
In that case set `JAVA_HOME` and prepend it to `PATH`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

## No-LLM First Run

No provider key is required for parser and schema inspection:

```bash
doctruth parse examples/no-llm-parse/sample-contract.csv
doctruth parse examples/no-llm-parse/sample-contract.csv --json -o parsed.json
doctruth schema examples/pydantic-interop/resume.schema.json
```

This is the recommended first-run path. It proves the document evidence surface
before a user spends time configuring model keys.

## Extraction Run

Extraction requires a provider key:

```bash
export OPENAI_API_KEY=...
doctruth extract contract.pdf -s contract.schema.json
doctruth audit .doctruth/runs/<run-id>/audit.json
```

Use `--provider`, `--model`, and `--base-url` only when the defaults are not
enough.

The CLI and SDK use the same parser, citation, provenance, and audit primitives.

## GitHub Release Artifacts

Tagged releases attach CLI artifacts:

```text
doctruth-<version>.tar.gz
doctruth-java-<version>-all.jar
checksums.txt
doctruth.rb
```

Use the tarball when you want a `bin/doctruth` launcher plus the bundled jar:

```bash
tar -xzf doctruth-0.2.0-alpha.tar.gz
JAVA=/path/to/java ./doctruth-0.2.0-alpha/bin/doctruth version
```

Use the all-jar when you want the simplest direct invocation:

```bash
java -jar doctruth-java-0.2.0-alpha-all.jar version
```

## Homebrew

The release workflow generates a formula for the `doctruthhq/homebrew-tap`
repository. Once the tap is updated:

```bash
brew tap doctruthhq/tap
brew install doctruth
doctruth version
doctruth doctor
```

See [Homebrew Distribution](homebrew.md) for maintainer details.
