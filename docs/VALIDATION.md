# Verification

Android compilation, Lint, tests and packaging run entirely in GitHub Actions. No Android SDK, JDK, Gradle, NDK, ADB or emulator is installed on the user's computer.

## Release gate

The release workflow requires a successful **Android CI run for the exact source commit**. It then runs the shared unit-test tasks and Release Lint, signs the APK, verifies its signature and publishes the APK with SHA256. The release notes link the successful source CI run; the release job summary repeats that link. The app's registered unit-test entry is `testDebugUnitTest`; the 52 engine tests are in `core:test` and all device tests are required by the source CI gate.

| Check | Required coverage |
|---|---|
| JVM unit tests | 52 tests; permission states, Manifest/AXML, DEX, domain boundaries, scoring, reports, authorization, command allowlist, rollback, socket ownership, DNS and IPv4/IPv6 TCP/UDP relay |
| Verification guard | 3 tests; zero, skipped or failed instrumentation must fail CI |
| Android 8 / API 26 | 7 actual instrumentation tests |
| Android 15 / API 35 | 7 actual instrumentation tests |
| Android 16 / API 36 | 7 actual instrumentation tests |
| Android Lint | No errors; dependency update and style advisories remain visible |

The seven device tests verify:

1. Real PackageManager metadata and installed APK analysis, report export, persisted result hydration and result display.
2. Import of an actual APK with a separate record and unknown installed authorization state.
3. Privileged command allowlist execution without Root; forbidden commands remain rejected.
4. Room v1 → v2 migration of a Unicode report larger than 2 MiB without oversized cursor rows.
5. First-run language selection, Chinese/English setting changes and persistence across Activity recreation.
6. A **separate application UID** sends TCP and UDP through the real VPN to runner-local echo servers. Both echoes, upload/download metadata, the fixture DNS query, Android 10+ owner UID and VPN stop must pass. Tests wait for batched database writes; they do not substitute shell traffic, which may bypass the VPN.

7. Expanded-window application navigation keeps the inventory and selected application detail visible together at a 1600 × 2560 px / 240 dpi emulator configuration. The test saves `tablet-list-detail.png`.

## Evidence artifacts

- `sososafe-debug`: installable APK and checksum.
- `sososafe-verification`: build logs, JUnit reports, Room schemas and full Lint output.
- `sososafe-device-verification-api-26/35/36`: raw instrumentation output, generated JUnit XML, device logcat, phone/dark/large-text/tablet screenshots, language screenshots and observed VPN metadata.
- `sososafe-release-verification`: Release build/test/Lint reports and signature verification output.

The instrumentation guard requires at least six terminal success results and a JUnit success summary. Empty, skipped and failed test runs cannot become green. Known **System UI / Quickstep emulator ANR overlays** may be dismissed by the test setup; auditor and traffic-fixture ANRs are never dismissed by that recovery code.

UI screenshots in the README come from GitHub emulator runs. Empty screenshots show the real initial state; they contain no fabricated scan data. Light, dark, large-text and tablet layouts were visually inspected in bounded passes.

## Verification boundary

Physical KernelSU, Magisk and APatch authorization, actual Shizuku pairing/service identity, OEM restrictions and physical Wi-Fi/mobile handovers require corresponding authorized device tests. The implementations exist, and mocked/pure policy tests cover their safety rules; emulator results do not certify every manager or OEM. HTTPS, DoH/DoT and ECH content is not inspected.

[Android CI](https://github.com/vexpaer/sososafe/actions/workflows/android-ci.yml) · [Release workflow](https://github.com/vexpaer/sososafe/actions/workflows/android-release.yml) · [v0.1.0](https://github.com/vexpaer/sososafe/releases/tag/v0.1.0)

## UI refresh screenshot review

The emulator verification job also captures these navigation states in `artifacts/screens/`:

- `apps.png` — application inventory and filters
- `app-detail.png` — selected application detail
- `network.png` — local VPN status and connection list
- `actions.png` — review queue and rollback history
- `settings.png` — appearance, access, storage, and report controls
- `overview.png` — dashboard after returning from the other destinations

The existing light, dark / large-text, and tablet screenshots remain part of the same run. Screenshots are reviewed alongside build and instrumentation results; they are not a substitute for Root, Shizuku, or physical-device validation.
