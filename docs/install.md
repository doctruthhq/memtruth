# Install DocTruth CLI

DocTruth's parser core is the Rust runtime. The Java SDK and CLI are wrappers
for application integration, packaging, and first-run inspection:

```text
document -> parsed sections with source locations -> schema check -> audit output
```

## SDK Install

Use the Java wrapper SDK when adding DocTruth to an application:

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.2.0-alpha</version>
</dependency>
```

Set the Rust runtime command for direct Maven/JAR usage:

```bash
export DOCTRUTH_RUNTIME_COMMAND=/path/to/doctruth-runtime
```

Minimal TrustDocument parser flow:

```java
var trustDoc = DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))
        .parsePdf(Path.of("contract.pdf"))
        .withParser(ParserPreset.STANDARD)
        .parse();
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

Install a `doctruth` launcher, the Rust parser runtime, and the Rust MNN model
worker:

```bash
cargo build --manifest-path runtime/doctruth-runtime/Cargo.toml --release --bins
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
doctruth-runtime --doctor
doctruth-mnn-model-worker --doctor
doctruth parse fixtures/pdf/ResumeAFIQDANISH.pdf --format json
```

The installed `doctruth` launcher discovers `bin/doctruth-runtime` and exports
`DOCTRUTH_RUNTIME_COMMAND` automatically. It also discovers
`bin/doctruth-mnn-model-worker` and exports `DOCTRUTH_RUNTIME_MODEL_COMMAND`
and `DOCTRUTH_MODEL_COMMAND` automatically. TrustDocument parse formats use the
Rust runtime by default after install. Use `--backend pdfbox` only for
legacy/oracle comparison during migration or regression debugging.

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
doctruth parse contract.pdf
doctruth parse contract.pdf --format json -o trust-document.json
doctruth ingest-audit ./resumes --json -o ingest-audit.json
doctruth schema contract.schema.json
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

Use the tarball when you want a `bin/doctruth` launcher, `bin/doctruth-runtime`,
`bin/doctruth-mnn-model-worker`, and the bundled jar:

```bash
tar -xzf doctruth-0.2.0-alpha.tar.gz
JAVA=/path/to/java ./doctruth-0.2.0-alpha/bin/doctruth version
```

Release tarballs do not include RapidOCR, SLANeXT/PaddleOCR, or ONNXRuntime
Python worker scripts. Those scripts remain in the source tree only as
legacy/oracle tools for migration comparisons. Production release packaging is
Rust runtime plus Rust MNN model worker. OCR/model files are not bundled inside
the Java jar; provide them through the local runtime package or
`DOCTRUTH_MODEL_CACHE` plus `DOCTRUTH_MODEL_MANIFEST`.

The release launcher also discovers its same-directory `doctruth-runtime` and
`doctruth-mnn-model-worker`, then sets `DOCTRUTH_RUNTIME_COMMAND`,
`DOCTRUTH_RUNTIME_MODEL_COMMAND`, and `DOCTRUTH_MODEL_COMMAND` automatically,
so packaged CLI parsing is Rust-first without extra environment setup.

Real layout/table model artifacts are not bundled. Use a manifest and the
opt-in real model smoke to validate a local artifact before relying on it:

```bash
DOCTRUTH_REAL_MODEL_MANIFEST=models.json \
DOCTRUTH_REAL_MODEL_PRESET=standard \
DOCTRUTH_REAL_MODEL_EXPECTED_TASK=layout-detection \
scripts/smoke-doctruth-real-model-artifact.sh
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
