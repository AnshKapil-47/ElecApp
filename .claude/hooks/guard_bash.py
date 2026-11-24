#!/usr/bin/env python3
"""PreToolUse hook for Bash: blocks commands that could destroy data, history or secrets.

Contract (Claude Code hooks): JSON on stdin with tool_input.command.
Print a JSON permission decision on stdout and exit 0; no output = no opinion.
"""
import json
import re
import sys

DENY = [
    (r"\bflyway(\s+|:)clean\b|\bflywayClean\b", "flyway clean wipes the whole database. Never run it."),
    (r"\bdrop\s+database\b(?!\s+(if\s+exists\s+)?\w*test\w*)", "DROP DATABASE is only allowed for *_test databases."),
    (r"\bdrop\s+schema\b", "Dropping a schema is not allowed."),
    (r"\b(truncate|delete\s+from|update)\s+(public\.)?audit_log\b", "audit_log is append-only."),
    (r"\b(update|delete\s+from|truncate)\s+(public\.)?agent_sale_line\b", "agent_sale_line is immutable sales history."),
    (r"\b(delete\s+from|truncate)\s+(public\.)?agent_sale\b", "Sales are never deleted (void them instead)."),
    (r"\bdelete\s+from\s+(public\.)?voter\b", "Delete voter data only by dropping the dataset partition in the deletion job."),
    (r"\bgit\s+push\b.*(\s--force\b|\s-f\b|\s--force-with-lease\b)", "Force-push is not allowed."),
    (r"\brm\s+-[a-z]*r[a-z]*f?[a-z]*\s+(/|~|\$HOME|\.|\*)(\s|$)", "Recursive delete of root, home, the project or * is not allowed."),
    (r"\b(cat|less|more|head|tail|type|bat)\b[^|;&]*\.env(\.[\w-]+)?(\s|$)", "Do not print .env files; secrets must stay out of the transcript."),
    (r"\b(printenv|env)\b\s*(\||$)", "Do not dump environment variables; they hold secrets."),
]
ASK = [
    (r"\bgit\s+push\b", "Pushing changes leaves this machine. Confirm."),
    (r"\bgit\s+reset\s+--hard\b|\bgit\s+clean\s+-[a-z]*f", "This discards local work. Confirm."),
    (r"\bpsql\b.*\b(prod|production)\b", "This looks like a production database. Confirm."),
]


def decide(cmd: str):
    low = cmd.lower()
    for pattern, reason in DENY:
        if re.search(pattern, low):
            return "deny", reason
    for pattern, reason in ASK:
        if re.search(pattern, low):
            return "ask", reason
    return None, None


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0  # never block on malformed input
    cmd = (data.get("tool_input") or {}).get("command") or ""
    decision, reason = decide(cmd)
    if decision:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": f"ElecSarthi guard: {reason}",
        }}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
