# Security Review

Find and remediate security weaknesses before code is committed or shipped.

## When to use

STOP and use this skill whenever a change touches authentication or authorization, user input,
database queries, filesystem operations, external/network calls, cryptography, or payment/financial
logic. Run it before any commit in these areas — security findings block the merge.

## Method

1. **Map the trust boundaries.** Identify where untrusted data enters (request params, file
   contents, API responses, env, CLI args) and where sensitive actions happen (queries, file writes,
   process exec, outbound requests). Every boundary is a place to validate or defend.
2. **Walk the OWASP-style checklist** below against those boundaries.
3. **For each finding, rate severity and give the fix:** `CRITICAL` (exploitable vulnerability or
   secret exposure — block), `HIGH` (weakness likely exploitable), `MEDIUM`/`LOW` (hardening).
4. **Fix CRITICAL issues before continuing.** If a secret was exposed, treat it as compromised:
   remove it from code/history and rotate it.
5. **Re-scan for siblings.** A vulnerability found in one spot usually has copies — grep for the
   same pattern across the codebase.

## Checklist

- [ ] **Secrets:** no hardcoded keys/passwords/tokens; secrets come from env or a secret manager;
      required secrets are validated at startup; nothing sensitive is logged or echoed in errors.
- [ ] **Injection:** queries are parameterized (no string-concatenated SQL); no shell/command
      injection from untrusted input; no unsafe deserialization.
- [ ] **Input validation:** all external input is validated/normalized at the boundary; fail fast on
      malformed data; never trust client-supplied lengths, paths, or types.
- [ ] **Path traversal:** file paths are resolved and confined; `../` and symlinks cannot escape the
      intended directory; sensitive files are not readable/writable through user input.
- [ ] **SSRF / outbound:** URLs from input are checked before fetch; requests to private/loopback/
      link-local/metadata addresses are blocked; redirects are not followed blindly.
- [ ] **AuthN/AuthZ:** every privileged action checks identity *and* permission; no authorization
      bypass via missing checks or client-trusted flags.
- [ ] **Output/encoding:** user-controlled output is escaped for its sink (HTML/JSON/log); error
      messages do not leak stack traces, internal IPs, or credentials.
- [ ] **Crypto:** use vetted libraries and current algorithms; never roll your own; use secure
      randomness for tokens.
- [ ] **Rate/DoS:** external endpoints are bounded (rate limits, size caps, timeouts, pagination).

## Output

Report findings grouped by severity, each with file/line, the concrete risk, and the remediation.
State clearly whether any `CRITICAL` issue blocks the change.
