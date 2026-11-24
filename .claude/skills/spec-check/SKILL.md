---
name: spec-check
description: Check a planned feature, design or the current git diff against ElecSarthi's business rules, permission matrix and out-of-scope list before building or merging. Use when someone proposes a feature, before a PR, or when unsure whether something belongs in the product.
context: fork
agent: spec-checker
argument-hint: "[feature description, or empty to check the current git diff]"
---

Check this against the ElecSarthi specification: $ARGUMENTS

If no description was given, check the current changes (`git diff` and `git diff --staged`).

Report:
1. **Verdict:** FITS / FITS WITH CHANGES / OUT OF SCOPE.
2. **Rules involved:** quote the exact lines from `.claude/rules/business-rules.md`, `.claude/rules/security.md` and `CLAUDE.md`.
3. **Conflicts:** anything that adds notifications, in-app requests or approvals, payments, new roles, dataset exposure, voter access for Owner, Subowner or Agent, mobile numbers outside the rule, a new table without a rule, or duplicate APIs.
4. **Missing pieces:** role check, tenant scope, audit entry, tests, docs or ER update.
5. **Questions for the Owner:** at most three, only where the rules are silent.
