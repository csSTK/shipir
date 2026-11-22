# Security policy

## Status

Shipir is a learning and portfolio project. It is built with security in mind (see "Security" in the README), but it has not had an external audit and is **not hardened for production use**. Run it only on hosts and networks you control, behind HTTPS, and never expose it directly to the internet.

Shipir talks to the Docker socket. Anyone who controls Shipir as an ADMIN effectively has root on every connected Docker host.

## Known issues

Security findings are tracked openly in the beads issue tracker (`.beads/issues.jsonl`, epic `shipr-kqbc` "Findings: Security & authorization"). Open findings there are known and not yet fixed.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub: **Security > Report a vulnerability** on this repository. Do not open a public issue for a vulnerability that is not already listed in the tracker.

Include the affected version or commit, steps to reproduce and the impact you expect. This is a one-person project, so responses are best effort.

## Supported versions

Only the latest release and the `dev` branch get fixes.
