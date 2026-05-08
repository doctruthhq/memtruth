# Error Handling

DocTruth errors are meant to be routed by code, not scraped from prose. Public
exceptions expose stable `errorCode()` values, retry metadata where relevant,
and the original cause when wrapping a lower-level failure.

## Integration Matrix

| Situation | Exception | Stable code or mapping | Caller action |
| --- | --- | --- | --- |
| Provider rejects auth or request over HTTP | `ProviderException` | `PROVIDER_HTTP_<status>`, for example `PROVIDER_HTTP_401` | Fix credentials/config for 4xx; retry/backoff for retryable 429/5xx. |
| Provider transport fails | `ProviderException` | `PROVIDER_TRANSPORT_FAILED` | Retry outside the call if your workflow allows it. |
| Provider returns malformed successful body | `ProviderException` | `PROVIDER_RESPONSE_INVALID` | Treat as provider/schema mismatch or upstream regression. |
| Extraction provider call fails | `ExtractionException` | `EXTRACTION_PROVIDER_FAILED` | Inspect the cause `ProviderException` for provider name/status/retryability. |
| LLM returns non-JSON or wrong Java record shape | `ExtractionException` | `EXTRACTION_PARSE_FAILED` | Retry with a clearer prompt or use JSON Schema mode. |
| JSON output violates caller schema | `ExtractionException` | `EXTRACTION_SCHEMA_VALIDATION_FAILED` | Allow configured repair retries; otherwise surface to review/fallback. |
| Custom field/object constraint fails | `ExtractionException` | `EXTRACTION_CONSTRAINT_FAILED` | Treat as business-rule failure, not provider outage. |
| Required citation cannot be matched strongly enough | `ExtractionException` | `EXTRACTION_EVIDENCE_MISSING` | Degrade, manual-review, or fall back to a stricter extraction prompt. |
| PDF cannot be parsed | `ParseException` | `PDF_PARSE_FAILED` | Route to OCR/repair/manual review. |
| DOCX/XLSX/CSV cannot be parsed | `ParseException` | `DOCX_PARSE_FAILED`, `XLSX_PARSE_FAILED`, `CSV_PARSE_FAILED` | Route to format-specific repair/fallback. |
| CLI Pydantic schema check fails | CLI exit code `1` | message starts `schema compatibility check failed` | Fix unsupported or unresolved `$ref` before using the schema. |
| CLI usage is invalid | CLI exit code `2` | usage message | Fix command-line arguments. |

## Provider Schema Normalization

Local validation is the source of truth. Provider-specific schema projection is
only a request-shaping strategy:

- Anthropic and OpenAI receive the caller schema unchanged when possible.
- Gemini receives a conservative projected schema with local `$ref` inlined and
  nullable unions converted to `nullable: true`.
- DeepSeek uses JSON object mode; DocTruth validates the returned JSON locally.

If a provider accepts a weaker schema than the caller supplied, DocTruth still
checks the full `JsonSchema` after the response and raises
`EXTRACTION_SCHEMA_VALIDATION_FAILED` on mismatch.

## Secret Safety

Provider keys are read from caller code or environment variables. DocTruth does
not print keys, copy keys into audit JSON, or include keys in exceptions. CLI
migration may invoke Python during export, but runtime Java extraction does not
depend on Python.
