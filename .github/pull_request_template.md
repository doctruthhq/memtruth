## Summary

<!-- What changed and why? -->

## Scope

<!-- Public API, parser, citation, provider, audit JSON, docs, tests, or release? -->

## Verification

- [ ] `cd java && mvn test`
- [ ] `cd java && mvn verify`
- [ ] `cd java && mvn spotless:check`
- [ ] `cd java && mvn checkstyle:check`
- [ ] `cd rust && cargo test`
- [ ] Documentation updated, if behavior changed

## Contract Checklist

- [ ] Public API changes are intentional and called out
- [ ] Source evidence, provenance, confidence, or audit semantics are preserved
- [ ] No new direct dependency without an ADR
- [ ] No real customer documents, secrets, API keys, or personal data included
- [ ] New behavior has a focused test at the closest contract boundary

## Notes For Reviewers

<!-- Anything risky, intentionally deferred, or worth special attention? -->
