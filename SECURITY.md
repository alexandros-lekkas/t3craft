# Security Policy

T3 Craft connects Minecraft to the coding agents on your computer, so we take security reports seriously.

## Supported versions

Only the **latest release** gets security fixes.

## Reporting a vulnerability

**Please don't open a public issue.** Use [GitHub private vulnerability reporting](https://github.com/alexandros-lekkas/t3craft/security/advisories/new) instead.

Include what an attacker could do, the steps to reproduce it, and the version. You'll get a reply within **72 hours**, and a fix or a plan within 14 days.

Especially in scope:
- anything that lets a **server, another player or a world file** send prompts, approve commands or read threads through someone's T3 Craft client
- leaks of tokens, pairing links or prompt contents (logs, crash reports, network)
- the release supply chain (jar, `.mrpack`, checksums)

Vulnerabilities in T3 Code itself go to [pingdotgg/t3code](https://github.com/pingdotgg/t3code/security).
