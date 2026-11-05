# Validation evidence

All Android compilation, Lint, unit and device tests run in GitHub Actions. No Android SDK/JDK/ADB was installed on the user's computer.

The current verification run and artifact URLs will be recorded after completion. An earlier failed run is not build evidence. Release signing is configured but cannot be claimed tested without the four production Secrets.

Coverage in CI: permission mapping; endpoint boundary classification; scoring caps and unknown states; Manifest defaults, inherited permissions and malformed XML; DEX string-table fixture and real APK scan; upstream Dumpsys/shared-UID fixtures; report roundtrip and formats; authorization mode selection; command injection allowlist and exact rollback commands; IPv4/IPv6 packet parsing; DNS compression/loops; authenticated TCP and UDP relays over both IP families; actual emulator TUN TCP/UDP echo forwarding; installed APK scanning/import/export; application startup and phone/light/dark/large-text/tablet screenshots.

Physical Root/KernelSU/Magisk/APatch authorization, real Shizuku wireless-debugging pairing and OEM-specific restrictions require authorized device testing. The code implements those paths; emulator/unit verification alone does not establish physical-device behavior. No physical device operations are authorized by this repository implementation request.
