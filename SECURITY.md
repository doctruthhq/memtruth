# Security Policy

DocTruth is an OSS Java library for auditable document extraction. Security
reports are welcome and should be handled privately before public disclosure.

## Supported Versions

| Version | Supported |
| --- | --- |
| `0.2.x-alpha` | Security fixes accepted on `main` |
| Older alpha releases | Best effort |

The project is pre-`1.0`, so public APIs may still change. Security fixes take
priority over compatibility when needed.

## Reporting A Vulnerability

Please do not open a public GitHub issue for suspected vulnerabilities.

Report security concerns by emailing:

```text
security@doctruth.ai
```

Include:

- affected version or commit
- environment details
- reproduction steps
- expected impact
- whether the issue involves secrets, source documents, audit JSON, provider
  calls, or generated artifacts

We aim to acknowledge valid reports within 5 business days.

## Sensitive Data

Do not attach real customer documents, secrets, API keys, credentials, or
regulated data to public issues, discussions, or pull requests. Use minimal
synthetic fixtures whenever possible.

## Scope

Security-sensitive areas include:

- parser behavior for untrusted PDF, DOCX, XLSX, and CSV files
- provider request and response handling
- prompt, source text, and audit JSON logging
- citation and provenance integrity
- dependency vulnerabilities
- CLI handling of local files and output paths

General bugs that do not affect confidentiality, integrity, availability, or
evidence correctness can be filed as normal GitHub issues.
