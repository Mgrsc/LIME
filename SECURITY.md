# Security Policy

## Supported Versions

We actively provide security updates and patches for the following versions of LIME:

| Version | Supported          | Target Platforms |
| ------- | ------------------ | ---------------- |
| 0.9.x   | :white_check_mark: | Android 12 – 16  |

---

## Security & Privacy Commitments

Input methods run continuously with elevated system visibility. LIME adheres to strict on-device security principles:

1. **Zero Cloud Telemetry**: Keystrokes, clipboard history, user-defined phrases, dynamic word-frequency memories, audio recordings, and handwriting strokes never leave the local device.
2. **Deterministic Offline AI Inference**: Speech recognition (SenseVoice) and handwriting recognition (PP-OCRv6) execute strictly on-device using local ONNX runtimes.
3. **Verified Model Delivery**: The `android.permission.INTERNET` permission is utilized solely upon explicit user confirmation to download immutable, fixed-version runtime libraries and neural weights from an allowlist over HTTPS. Every downloaded artifact is strictly validated against an internal SHA-256 integrity hash before execution.
4. **16KB Memory Page Alignment**: Native libraries target Android 16KB page-size compatibility. Alignment is a loading compatibility requirement, not a guarantee of memory safety; each release must verify its packaged and downloaded binaries.

---

## Reporting a Vulnerability

If you identify a security vulnerability in LIME (such as clipboard data exposure, unauthorized network transmission, local privilege escalation, or native memory safety defects), please report it responsibly:

1. **DO NOT file a public issue or discussion** detailing the security vulnerability.
2. **Submit via GitHub Private Vulnerability Reporting** (when enabled):
   - Go to the repository's **Security** tab -> **Advisories** -> **Report a vulnerability** ([Direct Link](https://github.com/Mgrsc/LIME/security/advisories/new)).
3. **Reporting by Email**:
   - If GitHub private reporting is unavailable, use email; do not open a public issue.
   - Send your report to **mail@occult.ac.cn** with the subject line `[SECURITY VULNERABILITY] LIME - <Brief Summary>`.

### What to Include in Your Report

To help us investigate and triage the issue quickly, please provide:
* Description and category of the vulnerability
* Affected version(s) and target environment (Device model, Android OS version)
* Step-by-step reproduction instructions or a minimal Proof-of-Concept (PoC)
* Potential impact assessment

### Response SLA

* **Initial Acknowledgment**: Within **48 hours** of receiving the report.
* **Triage & Validation**: Within **5 business days**, detailing confirmed severity and planned remediation steps.
* **Coordinated Disclosure**: A public security advisory and patched release will be coordinated once the fix is developed and verified.
