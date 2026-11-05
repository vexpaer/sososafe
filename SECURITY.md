# Security

Report vulnerabilities using the repository's private GitHub security reporting feature when available, otherwise create an issue with a minimal non-sensitive description and request a private contact method. Do not publish device data, APKs, reports, credentials or exploit details in a public issue.

The app is read-only by default. Root is requested only by deliberate UI action. VPN starts only after explanation and Android consent. Changes require a target-specific confirmation and a persisted original state. Application sandboxes and TLS are not bypassed.

AppOps and UID sharing can have effects beyond an individual permission declaration. Static references and domain signatures are review clues; they are not malware or exfiltration verdicts. Uninstall recovery restores APK files only, never deleted private data.

Debug builds are for direct testing. Production releases must use repository Secrets and a controlled signing key. Keep exported reports private and inspect their content before sharing.
