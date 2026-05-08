# DocTruth

<p align="center">
  <img src="docs/assets/readme-hero.png" alt="DocTruth source-cited extraction: every extracted field cites a source page and line">
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.zh-TW.md">繁體中文</a> ·
  <a href="README.es.md">Español</a>
</p>

[![CI](https://github.com/doctruthhq/DocTruth/actions/workflows/ci.yml/badge.svg)](https://github.com/doctruthhq/DocTruth/actions)
[![Maven Central](https://img.shields.io/maven-central/v/ai.doctruth/doctruth-java.svg?label=Maven%20Central)](#instalación)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25+-007396?logo=openjdk)](https://openjdk.org)

**Extracción LLM auditable para Java.** Analiza documentos, extrae datos estructurados y adjunta citas por campo, confianza y procedencia.

DocTruth existe para responder una pregunta:

> ¿De dónde salió este valor extraído?

No es un framework de agentes, un framework de chains, un wrapper de bases vectoriales ni una UI. Es una biblioteca Java pequeña para el límite de extracción: documento de entrada, salida estructurada validada y evidencia verificable.

## Instalación

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

```groovy
implementation "ai.doctruth:doctruth-java:0.1.0-alpha"
```

Requiere Java 25+.

## Inicio Rápido

```java
import ai.doctruth.DocTruth;
import ai.doctruth.OpenAiProvider;
import ai.doctruth.PdfDocumentParser;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

record Contract(String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue) {}

var doc = PdfDocumentParser.parse(Path.of("contract.pdf"));

var result = DocTruth.from(new OpenAiProvider(System.getenv("OPENAI_API_KEY")))
        .extract("Extract the contract terms", Contract.class)
        .withProvenance()
        .withConfidence()
        .withBitemporal()
        .run(doc);

Contract contract = result.value();
var partyACitation = result.citations().get("partyA");
```

Ejemplo ejecutable: [`examples/quickstart`](examples/quickstart/).

## Qué Hace

- Analiza PDF, DOCX, XLSX y CSV en secciones con ubicaciones de origen.
- Extrae Java records u objetos definidos por JSON Schema mediante proveedores LLM.
- Valida la salida estructurada localmente y reintenta fallos reparables.
- Vincula cada campo extraído con una cita exacta del documento fuente.
- Devuelve `Citation`, `Confidence` y `Provenance` por campo.
- Exporta archivos de auditoría W3C PROV-O JSON-LD con `toAuditJson(...)`.

## Interoperabilidad JSON Schema y Pydantic

Los Java records son la ruta nativa. JSON Schema es la ruta de interoperabilidad.

```java
var schema = JsonSchema.from(Path.of("contract.schema.json"));

var result = DocTruth.from(provider)
        .extractJson("Extract contract terms", schema)
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withMaxRetries(2)
        .runJson(doc);
```

DocTruth soporta exportaciones comunes de Pydantic v2 JSON Schema, incluyendo `$defs` / `$ref` locales, uniones nullable, objetos anidados, arrays, enums, campos requeridos, restricciones escalares y `additionalProperties=false`.

Herramienta de migración en build-time:

```bash
java -jar target/doctruth-java-0.1.0-alpha.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  --out schemas/resume.schema.json \
  --check
```

La extracción Java en producción solo necesita el archivo schema exportado y el jar de DocTruth.

## Proveedores

OpenAI-compatible chat completions es la ruta principal porque muchos modelos alojados, gateways y modelos locales exponen esa forma de API.

| Provider | Modo de salida estructurada |
| --- | --- |
| OpenAI / OpenAI-compatible | `response_format: json_schema` |
| Anthropic | tool-use forcing |
| Gemini | `responseMimeType` + `responseSchema` |
| DeepSeek | JSON mode compatible con OpenAI + validación local |

Los clientes usan `java.net.http.HttpClient` del JDK; no hay SDKs de proveedores en el classpath.

## CLI

```bash
java -jar target/doctruth-java-0.1.0-alpha.jar parse contract.pdf
java -jar target/doctruth-java-0.1.0-alpha.jar migrate pydantic myapp.schemas:Model --out schema.json --check
```

## Documentación

- [Ejemplo quickstart](examples/quickstart/)
- [Ejemplo Pydantic interop](examples/pydantic-interop/)
- [Arquitectura](docs/architecture/auditable-structured-extraction-engine.md)
- [Manejo de errores](docs/error-handling.md)
- [Proceso de release](docs/release.md)
- [Contribuir](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Estado

`0.1.0-alpha` es una alpha pública temprana. La API es usable, está probada y se publica para recibir feedback, pero puede cambiar antes de `1.0`.

Baseline actual: 628 unit tests y 16 integration tests pasando, con 2 smoke tests externos omitidos, coverage gates en 90% line / 80% branch, jar único de aproximadamente 202 KB.

## Licencia

Código bajo [Apache License 2.0](LICENSE).

`DocTruth`, `doctruth.ai` y el logo de DocTruth son marcas de doctruthhq. Ver [NOTICE](NOTICE).
