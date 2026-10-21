#!/usr/bin/env python3
"""PreToolUse hook (Bash): database writes need the user's consent.

Read-only database access passes through to the normal permission flow.
Anything that writes, or that can't be shown to be read-only, gets
permissionDecision "ask" so the user has to approve it. Testcontainers
databases (via ./mvnw test/verify) and beads (bd) are not affected.
"""
import json
import re
import sys

# Tools that always change a database or its storage.
ALWAYS_WRITE = [
    (r"\bpg_restore\b", "pg_restore"),
    (r"\b(createdb|dropdb|createuser|dropuser)\b", "database/user admin tool"),
    (r"\b(flyway|liquibase)\b", "schema migration tool"),
    (r"spring-boot:run\b", "starting the app runs Flyway migrations against the configured database"),
    (r"\bdocker\s+volume\s+(rm|prune)\b", "deleting Docker volumes (database data)"),
    (r"\bdocker(-compose|\s+compose)\b.*\bdown\b.*(\s-v\b|--volumes\b)", "compose down with volumes deletes database data"),
    (r"\b(mongosh|mongo|redis-cli)\b", "database client without read-only classification"),
]

# SQL clients whose statements can be classified.
SQL_CLIENT = re.compile(r"\b(psql|pgcli|mysql|mariadb|sqlite3)\b")

WRITE_SQL = re.compile(
    r"\b(insert|update|delete|merge|upsert|replace|truncate|drop|alter|create|grant|revoke|"
    r"copy|vacuum|reindex|cluster|refresh|call|do|lock|comment|security|import|into|"
    r"nextval|setval|pg_terminate_backend|pg_cancel_backend|lo_import|lo_unlink)\b"
    r"|\\(i|ir|copy|o|gset|set)\b|set\s+role|set\s+session",
    re.IGNORECASE,
)

# psql/sqlite3 options that run SQL from a file or stdin we can't see.
OPAQUE_INPUT = re.compile(r"(\s-f\b|\s--file\b|\s<\s*\S|<<|\|\s*(psql|pgcli|mysql|mariadb|sqlite3)\b)")
INLINE_SQL = re.compile(r"(\s-c\b|\s--command\b|\s-e\b|\s--execute\b|sqlite3\s+\S+\s+[\"'])")


def ask(reason: str) -> None:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": f"Database write guard: {reason}. Writes need explicit user consent.",
        }
    }))
    sys.exit(0)


def main() -> None:
    try:
        data = json.load(sys.stdin)
        cmd = data.get("tool_input", {}).get("command", "") or ""
    except Exception:
        ask("could not parse the command")
        return

    for pattern, reason in ALWAYS_WRITE:
        if re.search(pattern, cmd, re.IGNORECASE):
            ask(reason)

    client = SQL_CLIENT.search(cmd)
    if client:
        # Only inspect the client's own arguments (e.g. not `docker compose -f ...` before it),
        # but keep a pipe *into* the client visible.
        before, segment = cmd[: client.start()], cmd[client.start():]
        if re.search(r"\|\s*$", before) or OPAQUE_INPUT.search(" " + segment):
            ask("SQL from a file, stdin or pipe can't be checked for writes")
        if not INLINE_SQL.search(" " + segment):
            ask("interactive SQL session")
        if WRITE_SQL.search(segment):
            ask("SQL statement that may modify data or schema")
    # Read-only or not database-related: no decision, normal permission flow applies.


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        ask("guard failed to evaluate the command")
