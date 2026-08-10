# Security Policy

LocaPeer is a privacy-focused app: location data, messages, and control payloads are end-to-end encrypted before they leave the device. We take security reports seriously and ask that you do too - please **do not** file public issues or open PRs for suspected vulnerabilities.

## Reporting a Vulnerability

Use GitHub's **private vulnerability reporting** to disclose issues confidentially:

**[Report a vulnerability](https://github.com/daygle/LocaPeer/security/advisories/new)**

Only the maintainers can see private reports - nothing is made public until a fix is available.

Please include:

- The affected app version (from Settings → About) and device / Android version
- Clear, minimal steps to reproduce
- A description of the impact and your suggested severity, if you have one
- Where possible, a proof of concept without exposing other users' data

## Response Commitment

- **Acknowledgment:** within **3 business days** of your report
- **Status update:** within **7 business days** (triaged, reproducing, or fixed)
- **Fix & disclosure:** we aim to ship a fix before publicly disclosing, following coordinated disclosure once a patched release is available

## Scope

We're especially interested in issues involving:

- The end-to-end encryption implementation (NIP-44 v2, key handling, Schnorr signatures)
- Private key storage and the Android Keystore integration
- Unauthorized access to location data or messages (e.g., replay, cross-contact leakage)
- Privacy controls not being honored (precision modes, pause, retention windows, deletion)

Out-of-scope: phishing, social engineering, or issues in third-party libraries without a clear path to exploitation in this app.

## Security Contact

For anything that cannot go through GitHub (e.g., critical active exploitation), email the maintainer via the contact listed on the [repository profile](https://github.com/daygle/LocaPeer).
