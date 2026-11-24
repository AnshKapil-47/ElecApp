#!/usr/bin/env python3
"""PreToolUse hook for Edit/Write: protects secrets, generated files, applied migrations
and the guardrails themselves.

Contract (Claude Code hooks): JSON on stdin with tool_input.file_path.
Print a JSON permission decision on stdout and exit 0; no output = no opinion.
"""
import json
import os
import re
import sys


def rel(path: str) -> str:
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    try:
        r = os.path.relpath(os.path.abspath(path), os.path.abspath(root))
    except ValueError:  # different drive on Windows
        r = path
    return r.replace("\\", "/")


def decide(path: str):
    p = rel(path)
    name = os.path.basename(p).lower()

    # secrets: never written by Claude
    if name == ".env" or name.startswith(".env.") and not name.endswith(".example"):
        return "deny", "Secrets files are managed by people, not Claude. Use .env.example for templates."
    if re.search(r"\.(pem|key|jks|p12|keystore)$", name) or "/secrets/" in f"/{p}":
        return "deny", "Key and secret material must never be created or edited by Claude."

    # generated / owner-maintained data files
    if p.endswith("docs/data-model/election_type_field_seed.csv"):
        return "deny", ("This CSV is generated from NEW_APP_COLUMN_DETAILS.xlsx. Change the sheet, then run "
                        "python3 tools/seed/gen_seed.py docs/data-model/NEW_APP_COLUMN_DETAILS.xlsx "
                        "docs/data-model/election_type_field_seed.csv")
    if p.endswith(".xlsx"):
        return "deny", "Excel files are owned by the Owner; do not modify them."

    # Flyway: an existing versioned migration must not change once applied anywhere shared
    if re.search(r"db/migration/V\d+__.+\.sql$", p) and os.path.exists(path):
        return "ask", ("Editing an existing Flyway migration. Allowed only if it has never been applied to a "
                       "shared database; otherwise create a new V<next>__*.sql migration instead.")

    # guardrails themselves
    if p.startswith(".claude/hooks/") or p == ".claude/settings.json":
        return "ask", "This changes Claude's own guardrails. Confirm."
    return None, None


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0
    path = (data.get("tool_input") or {}).get("file_path") or ""
    if not path:
        return 0
    decision, reason = decide(path)
    if decision:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": f"ElecSarthi guard: {reason}",
        }}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
