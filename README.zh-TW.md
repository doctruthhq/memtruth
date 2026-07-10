# Memtruth SDK - 面向 AI 應用的證據與解析 SDK

<p align="center">
  <img src="docs/assets/readme-hero.png" alt="DocTruth source-cited extraction: every extracted field cites a source page and line">
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.zh-TW.md">繁體中文</a> ·
  <a href="README.es.md">Español</a>
</p>

[![CI](https://github.com/doctruthhq/memtruth/actions/workflows/ci.yml/badge.svg)](https://github.com/doctruthhq/memtruth/actions)
[![Maven Central](https://img.shields.io/maven-central/v/ai.doctruth/doctruth-java.svg?label=Maven%20Central)](#安裝)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25+-007396?logo=openjdk)](https://openjdk.org)

**Memtruth SDK 是面向 evidence-backed AI 應用的開源 SDK。** 它包含文件解析、來源可追溯擷取、語料契約、chunking、retrieval projection 和本地診斷。

文件證據模組叫 **Memtruth Parse，原 DocTruth**。遷移期間，公開 Java package、Maven 座標、CLI 命令、release artifacts 和 `doctruth` runtime 名稱都作為相容面保留。

Memtruth SDK 不包含長期記憶 server、MCP runtime、storage engine 或 replay service 實作；這些屬於獨立的 `memtruth-server` 方向。

Memtruth Parse 將 PDF、DOCX、XLSX 和 CSV 轉成 schema-bound structured output，並為每個欄位附上來源引用、可選 PDF bounding box、信心分數、provenance 和 PROV-O audit JSON。

Memtruth Parse 主要回答一個問題：

> 這個擷取值從哪裡來？

它不是 agent 框架、chain 框架、向量資料庫封裝，也不是 UI。它只專注於擷取邊界：輸入來源文件，輸出已驗證的結構化結果和證據鏈。

它不綁定框架，可以放進 plain Java、Spring Boot、LangChain4j、Spring AI、Quarkus、Micronaut，或任何已經在呼叫 OpenAI、Anthropic、Gemini、DeepSeek 或 OpenAI-compatible endpoint 的 Java 服務。

## 安裝

需要 Java 25+。驗證 Maven Central 可用：

```bash
mvn dependency:get -Dartifact=ai.doctruth:doctruth-java:0.2.0-alpha
```

在 Maven 專案中使用：

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.2.0-alpha</version>
</dependency>
```

Gradle 使用同一個座標：`ai.doctruth:doctruth-java:0.2.0-alpha`。

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

Memtruth Parse 支援常見 Pydantic v2 JSON Schema 輸出，包括本地 `$defs` / `$ref`、nullable unions、巢狀物件、陣列、列舉、required 欄位、純量約束和 `additionalProperties=false`。

建置期遷移工具：

```bash
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  -o schemas/resume.schema.json \
  --check
```

生產環境 Java 擷取只需要匯出的 schema 檔案和目前相容的 DocTruth jar。

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
mvn -f java/pom.xml package -DskipTests
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar parse contract.pdf
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar schema contract.schema.json
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar extract contract.pdf -s contract.schema.json
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

`0.2.0-alpha` 是早期公開 alpha。API 已可用、已測試、可供回饋，但在 `1.0` 前仍可能調整。

目前驗證基線：645 個 unit test 和 16 個 integration test 通過，2 個外部 smoke test 跳過，覆蓋率門檻為 90% line / 80% branch，單 jar 約 205 KB。

## License

程式碼使用 [Apache License 2.0](LICENSE)。

`Memtruth`、`Memtruth Parse`、`DocTruth`、`doctruth.ai` 和相關 logo 是 doctruthhq 的商標。見 [NOTICE](NOTICE)。
