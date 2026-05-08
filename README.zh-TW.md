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
[![Maven Central](https://img.shields.io/maven-central/v/ai.doctruth/doctruth-java.svg?label=Maven%20Central)](#安裝)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25+-007396?logo=openjdk)](https://openjdk.org)

**面向 Java 的可稽核 LLM 擷取函式庫。** 解析文件、擷取結構化資料，並為每個欄位附上來源引用、信心分數和 provenance。

DocTruth 主要回答一個問題：

> 這個擷取值從哪裡來？

它不是 agent 框架、chain 框架、向量資料庫封裝，也不是 UI。它只專注於擷取邊界：輸入來源文件，輸出已驗證的結構化結果和證據鏈。

## 安裝

需要 Java 25+。Gradle 可以使用同一個座標：`ai.doctruth:doctruth-java:0.1.0-alpha`。

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

升級到最新 release：

```bash
mvn versions:use-latest-releases -Dincludes=ai.doctruth:doctruth-java -DgenerateBackupPoms=false
```

## 快速開始

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

完整範例見 [`examples/quickstart`](examples/quickstart/)。

## 能力

<p align="center">
  <img src="docs/assets/capabilities.png" alt="DocTruth capabilities: parse, assemble context, extract with LLM providers, validate schema, attach evidence, and export audit JSON">
</p>

- 將 PDF、DOCX、XLSX、CSV 解析成帶來源位置的 sections。
- 透過 LLM provider 擷取 Java records 或 JSON Schema 約束的物件。
- 在本地驗證結構化輸出，並對可修復錯誤自動 retry。
- 將擷取欄位匹配回來源文件中的精確 quote。
- 回傳欄位級 `Citation`、`Confidence`、`Provenance`。
- 透過 `toAuditJson(...)` 匯出 W3C PROV-O JSON-LD 稽核檔案。

## JSON Schema 和 Pydantic 互通

Java record 是原生路徑。JSON Schema 是跨語言互通路徑。

```java
var schema = JsonSchema.from(Path.of("contract.schema.json"));

var result = DocTruth.from(provider)
        .extractJson("Extract contract terms", schema)
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withMaxRetries(2)
        .runJson(doc);
```

DocTruth 支援常見 Pydantic v2 JSON Schema 輸出，包括本地 `$defs` / `$ref`、nullable unions、巢狀物件、陣列、列舉、required 欄位、純量約束和 `additionalProperties=false`。

建置期遷移工具：

```bash
java -jar target/doctruth-java-0.1.0-alpha.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  --out schemas/resume.schema.json \
  --check
```

生產環境 Java 擷取只需要匯出的 schema 檔案和 DocTruth jar。

## Provider

OpenAI-compatible chat completions 是主要路徑，因為很多託管模型、閘道和本地模型都相容這個 API 形態。

| Provider | 結構化輸出方式 |
| --- | --- |
| OpenAI / OpenAI-compatible | `response_format: json_schema` |
| Anthropic | tool-use forcing |
| Gemini | `responseMimeType` + `responseSchema` |
| DeepSeek | OpenAI-compatible JSON mode + 本地驗證 |

Provider client 使用 JDK `java.net.http.HttpClient`，不引入 vendor SDK。

## CLI

```bash
java -jar target/doctruth-java-0.1.0-alpha.jar parse contract.pdf
java -jar target/doctruth-java-0.1.0-alpha.jar migrate pydantic myapp.schemas:Model --out schema.json --check
```

## 文件

- [Quickstart 範例](examples/quickstart/)
- [Pydantic 互通範例](examples/pydantic-interop/)
- [架構說明](docs/architecture/auditable-structured-extraction-engine.md)
- [錯誤處理](docs/error-handling.md)
- [發布流程](docs/release.md)
- [貢獻指南](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## 狀態

`0.1.0-alpha` 是早期公開 alpha。API 已可用、已測試、可供回饋，但在 `1.0` 前仍可能調整。

目前驗證基線：628 個 unit test 和 16 個 integration test 通過，2 個外部 smoke test 跳過，覆蓋率門檻為 90% line / 80% branch，單 jar 約 202 KB。

## License

程式碼使用 [Apache License 2.0](LICENSE)。

`DocTruth`、`doctruth.ai` 和 DocTruth logo 是 doctruthhq 的商標。見 [NOTICE](NOTICE)。
