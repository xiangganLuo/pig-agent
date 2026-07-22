---
name: networking-diagnostics
description: Check connectivity, DNS, ports and HTTP endpoints.
keywords: network, dns, ping, curl, port, http, connectivity, nslookup, test-netconnection, bash, powershell
version: 1.0.0
---
# Networking Diagnostics

Diagnose "can't reach it" problems layer by layer: is the host up, does DNS resolve, is the port open,
does the HTTP endpoint respond?

## When to use

Use this skill when a request times out or is refused, an API is unreachable, DNS looks wrong, or you
need to confirm a service is actually listening and responding before blaming the app.

## Diagnose in layers (stop at the first failure)

| Layer | bash / zsh | PowerShell |
|-------|------------|------------|
| 1. Reachable (ICMP) | `ping -c 4 host` | `Test-Connection host -Count 4` |
| 2. DNS resolves | `nslookup host` / `dig +short host` | `Resolve-DnsName host` |
| 3. TCP port open | `nc -vz host 443` | `Test-NetConnection host -Port 443` |
| 4. HTTP responds | `curl -sSI https://host/path` | `Invoke-WebRequest https://host/path -Method Head` |

## HTTP probing (prefer `fetchUrl`)

For fetching a URL's content, use pig's **`fetchUrl`** tool — it is SSRF-guarded (blocks internal/private
addresses) and cross-platform. Use `curl`/`Invoke-WebRequest` for lower-level checks:

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Status + headers only | `curl -sSI https://host` | `Invoke-WebRequest https://host -Method Head` |
| Show status code | `curl -s -o /dev/null -w '%{http_code}' https://host` | `(Invoke-WebRequest https://host).StatusCode` |
| Follow redirects | `curl -sSIL https://host` | `Invoke-WebRequest https://host -MaximumRedirection 5` |
| Timeout | `curl --max-time 5 https://host` | `Invoke-WebRequest https://host -TimeoutSec 5` |

## Method

1. **Work the layers in order** (reachable → DNS → port → HTTP) and **stop at the first failure** — that
   layer is your problem. Testing HTTP when DNS is broken just wastes time.
2. **Compare against a known-good target** (e.g. `ping 1.1.1.1`, `curl https://example.com`) to tell a
   *local* network problem from a *specific-host* problem.
3. **Distinguish the failure mode:** timeout (firewall/port closed/host down) vs. connection refused
   (nothing listening on that port) vs. DNS error (name won't resolve) vs. HTTP 4xx/5xx (reached the app,
   app-level error).
4. **Report the finding**, not just "it fails": which layer broke and the exact error/status.

## Safety / Anti-patterns

- Do not put credentials or tokens on the command line in cleartext (they leak into history/logs); use
  `fetchUrl` or a header from an env var, and never echo the secret.
- Respect the SSRF guard — do not try to reach internal/metadata endpoints through it.
- Don't loop a request forever; add a timeout and back off.

## Checklist

- [ ] I tested reachability → DNS → port → HTTP in order and stopped at the first failure.
- [ ] I compared against a known-good target to isolate local vs. remote.
- [ ] I named the failure mode (timeout / refused / DNS / HTTP status).
- [ ] No secret appeared on the command line.
