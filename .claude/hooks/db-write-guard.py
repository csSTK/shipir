#!/usr/bin/env python3
"""PreToolUse hook (Bash): database writes need the user's consent.

Only commands that actually *run* a database tool are inspected: the tool has
to be in command position (start of a segment after ; && || | ( or a newline,
after wrappers like env/sudo/time, inside `sh -c`, or inside docker exec/run).
Mentions in commit messages, grep patterns or bd text are ignored.

Read-only database access passes through to the normal permission flow.
Anything that writes, or that can't be shown to be read-only, gets
permissionDecision "ask" so the user has to approve it. Testcontainers
databases (via ./mvnw test/verify) and beads (bd) are not affected.
"""
import json
import os
import re
import shlex
import sys

SQL_CLIENTS = {"psql", "pgcli", "mysql", "mariadb", "sqlite3"}
ALWAYS_WRITE_TOOLS = {
    "pg_restore": "pg_restore",
    "createdb": "database/user admin tool",
    "dropdb": "database/user admin tool",
    "createuser": "database/user admin tool",
    "dropuser": "database/user admin tool",
    "flyway": "schema migration tool",
    "liquibase": "schema migration tool",
    "mongosh": "database client without read-only classification",
    "mongo": "database client without read-only classification",
    "redis-cli": "database client without read-only classification",
}
DB_TOOLS = SQL_CLIENTS | set(ALWAYS_WRITE_TOOLS)

# Wrappers that run the next word as the command.
WRAPPERS = {"env", "sudo", "time", "nice", "nohup", "exec", "command", "xargs", "watch", "timeout"}
SHELLS = {"sh", "bash", "zsh", "dash"}
BUILD_TOOLS = {"mvn", "mvnw", "gradle", "gradlew"}
APP_START_GOALS = re.compile(r"^(spring-boot:run|flyway:.*|liquibase:.*|bootRun|flyway\w+)$")

WRITE_SQL = re.compile(
    r"\b(insert|update|delete|merge|upsert|replace|truncate|drop|alter|create|grant|revoke|"
    r"copy|vacuum|reindex|cluster|refresh|call|do|lock|comment|security|import|into|"
    r"nextval|setval|pg_terminate_backend|pg_cancel_backend|lo_import|lo_unlink)\b"
    r"|\\(i|ir|copy|o|gset|set)\b|set\s+role|set\s+session",
    re.IGNORECASE,
)
INLINE_SQL_FLAGS = {"-c", "--command", "-e", "--execute"}
FILE_FLAGS = {"-f", "--file"}

# Fallback when parsing fails: any mention of a DB tool counts.
MENTION = re.compile(r"\b(" + "|".join(re.escape(t) for t in sorted(DB_TOOLS)) + r"|spring-boot:run)\b", re.IGNORECASE)


class Ask(Exception):
    pass


def ask(reason: str) -> None:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": f"Database write guard: {reason}. Writes need explicit user consent.",
        }
    }))
    sys.exit(0)


def split_segments(cmd: str):
    """Split a shell command into (separator_before, text) segments.

    Quote-aware; separators are ; & | ( ) ` and newlines outside quotes.
    Heredoc bodies are skipped (the `<<` stays in its segment).
    """
    segments, buf, sep_before = [], [], ""
    i, n = 0, len(cmd)
    quote = None
    pending_heredocs = []

    def flush(sep):
        nonlocal buf, sep_before
        text = "".join(buf).strip()
        if text:
            segments.append((sep_before, text))
        buf = []
        sep_before = sep

    while i < n:
        c = cmd[i]
        if quote == "'":
            buf.append(c)
            if c == "'":
                quote = None
            i += 1
            continue
        if quote == '"':
            buf.append(c)
            if c == "\\" and i + 1 < n:
                buf.append(cmd[i + 1])
                i += 2
                continue
            if c == '"':
                quote = None
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            buf.append(c + cmd[i + 1])
            i += 2
            continue
        if c in "'\"":
            quote = c
            buf.append(c)
            i += 1
            continue
        if cmd.startswith("<<<", i):
            buf.append("<<<")
            i += 3
            continue
        if cmd.startswith("<<", i):
            m = re.match(r"<<(-?)\s*(['\"]?)([^\s'\";&|()]+)\2", cmd[i:])
            if m:
                pending_heredocs.append(m.group(3))
                buf.append(m.group(0))
                i += len(m.group(0))
                continue
        if c == "\n":
            flush("\n")
            i += 1
            # Skip heredoc bodies that start after this newline.
            while pending_heredocs:
                delim = pending_heredocs.pop(0)
                while i < n:
                    end = cmd.find("\n", i)
                    line = cmd[i:] if end == -1 else cmd[i:end]
                    i = n if end == -1 else end + 1
                    if line.strip() == delim:
                        break
            continue
        if c in ";&|()`":
            sep = c
            if c in "&|" and i + 1 < n and cmd[i + 1] == c:
                sep = c + c
                i += 1
            flush(sep)
            i += 1
            continue
        buf.append(c)
        i += 1
    if quote:
        raise ValueError("unbalanced quotes")
    flush("")
    return segments


def tokenize(text: str):
    lex = shlex.shlex(text, posix=True, punctuation_chars="<>")
    lex.whitespace_split = True
    return list(lex)


def command_start(tokens):
    """Index of the actual command word, skipping VAR=x assignments and wrappers."""
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", t):
            i += 1
        elif os.path.basename(t) in WRAPPERS:
            i += 1
            # Skip the wrapper's options (and option values like `sudo -u postgres`, `timeout 5`).
            while i < len(tokens) and (tokens[i].startswith("-") or re.match(r"^\d+[smhd]?$", tokens[i])):
                takes_value = tokens[i] in {"-u", "-g", "-n", "-I", "-L", "-P", "-s"}
                i += 2 if takes_value else 1
        else:
            return i
    return len(tokens)


def check_sql_client(tokens, sep_before, raw):
    """tokens start at the SQL client."""
    if sep_before == "|":
        raise Ask("SQL piped into a client can't be checked for writes")
    args = tokens[1:]
    if any(a in FILE_FLAGS or a.startswith("--file=") or (a.startswith("-f") and len(a) > 2 and not a.startswith("--"))
           for a in args) or any(a in {"<", "<<", "<<<"} for a in args):
        raise Ask("SQL from a file or stdin can't be checked for writes")
    sqlite_inline = os.path.basename(tokens[0]) == "sqlite3" and len([a for a in args if not a.startswith("-")]) >= 2
    if not (any(a in INLINE_SQL_FLAGS or a.startswith(("--command=", "--execute=")) for a in args) or sqlite_inline):
        raise Ask("interactive SQL session")
    if WRITE_SQL.search(" ".join(args)):
        raise Ask("SQL statement that may modify data or schema")


def check_db_tool(tokens, sep_before, raw):
    name = os.path.basename(tokens[0])
    if name in ALWAYS_WRITE_TOOLS:
        raise Ask(ALWAYS_WRITE_TOOLS[name])
    if name in SQL_CLIENTS:
        check_sql_client(tokens, sep_before, raw)


def check_segment(sep_before, raw, depth=0):
    tokens = tokenize(raw)
    start = command_start(tokens)
    if start >= len(tokens):
        return
    tokens = tokens[start:]
    name = os.path.basename(tokens[0])

    if name in DB_TOOLS:
        check_db_tool(tokens, sep_before, raw)
        return

    if name in SHELLS and "-c" in tokens:
        idx = tokens.index("-c")
        if idx + 1 < len(tokens) and depth < 3:
            check_command(tokens[idx + 1], depth + 1)
        return
    if name == "eval" and depth < 3:
        check_command(" ".join(tokens[1:]), depth + 1)
        return

    if name in BUILD_TOOLS:
        if any(APP_START_GOALS.match(t) for t in tokens[1:] if not t.startswith("-")):
            raise Ask("starting the app or running migrations writes to the configured database")
        return

    if name == "java" and "-jar" in tokens:
        raise Ask("starting the app runs Flyway migrations against the configured database")

    if name in {"docker", "podman", "docker-compose"}:
        args = tokens[1:]
        words = [a for a in args if not a.startswith("-")]
        if name != "docker-compose" and words[:1] == ["volume"] and len(words) > 1 and words[1] in {"rm", "prune"}:
            raise Ask("deleting Docker volumes (database data)")
        if words[:2] == ["system", "prune"] and "--volumes" in args:
            raise Ask("deleting Docker volumes (database data)")
        is_compose = name == "docker-compose" or "compose" in words[:1]
        if is_compose and "down" in words and ("-v" in args or "--volumes" in args):
            raise Ask("compose down with volumes deletes database data")
        if "exec" in words or "run" in words:
            for k, t in enumerate(args):
                if os.path.basename(t) in DB_TOOLS:
                    check_db_tool(args[k:], sep_before, raw)
                    break
        return

    if name == "kubectl" and "exec" in tokens:
        for k, t in enumerate(tokens):
            if os.path.basename(t) in DB_TOOLS:
                check_db_tool(tokens[k:], sep_before, raw)
                break


def check_command(cmd: str, depth=0):
    for sep_before, raw in split_segments(cmd):
        check_segment(sep_before, raw, depth)


def main() -> None:
    try:
        data = json.load(sys.stdin)
        cmd = data.get("tool_input", {}).get("command", "") or ""
    except Exception:
        ask("could not parse the command")
        return

    try:
        check_command(cmd)
    except Ask as e:
        ask(str(e))
    except Exception:
        # Couldn't parse the shell syntax: fail closed only if a DB tool is mentioned at all.
        if MENTION.search(cmd):
            ask("could not parse a command that mentions a database tool")
    # Read-only or not database-related: no decision, normal permission flow applies.


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        ask("guard failed to evaluate the command")
