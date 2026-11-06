# SoSoSafe interface system

## Product direction

SoSoSafe is an evidence-first Android privacy inspector. Its interface should feel like a calm inspection instrument: clear status, readable evidence, direct controls, and a predictable path from an application to a finding and a reversible action.

## Platform and principles

- **Material 3 Expressive** provides Compose components, semantic color roles, shape, and restrained state motion.
- **Android conventions remain native:** system permission flows, bottom navigation on compact windows, navigation rail on expanded windows, and Android back behavior.
- Apply Apple's interface principles at the product level: clarity, deference to content, immediate feedback, consistency, user control, accessibility, and careful craft. Do not imitate iOS navigation or controls.
- FlClash is a visual reference for restrained Material You surfaces, quiet outlines, useful density, and a clear hierarchy. Do not copy its artwork or code.

## Visual system

- Dynamic Android color is the default on API 31+. Manual teal, blue, and plum accents are available when dynamic color is disabled or unavailable. The two modes are mutually exclusive.
- Keep page backgrounds neutral. Reserve accent for primary actions, selection, and meaningful status.
- Risk uses semantic color **and** a visible severity label. A numeric risk score is a review-priority signal, not a malware verdict.
- Use a small number of grouped surfaces. Lists remain flat; dividers and spacing establish row hierarchy. Avoid a card around every setting, permission, connection, or finding.
- Use a 4 dp spacing base, 16–20 dp group rhythm, and consistent 12–16 dp row geometry. Larger expressive shapes belong to focal surfaces, not every control.
- Keep typography roles distinct: screen title, section title, body, and supporting evidence. Do not shrink meaningful text to fit.
- Use short spring motion for expanding evidence rows. Avoid decorative animation that competes with inspection tasks.

## Information architecture

Five primary destinations remain stable:

1. Overview — scan state, app count, severity counts, distinct APK host references, and the next items to review.
2. Apps — searchable inventory, filters, sort order, and application detail.
3. Network — local VPN state, per-app traffic, connection metadata, and visibility boundaries.
4. Actions — findings that need review and the history of confirmed device changes.
5. Settings — language, appearance, access mode, scan scope, retention, export, and recovery.

Application detail uses five tabs: Overview, Permissions, Network, Technical, and Findings. On expanded windows, Apps uses a list-detail layout; compact windows use a single-pane flow.

## Copy and evidence

Every visible string must serve navigation, identity, value, action, feedback, or essential guidance. Remove subtitles that restate a heading, helper text that repeats a control, decorative captions, and tiny explanatory filler. Put non-obvious or consequential explanations behind an explicit information action.

Keep evidence categories distinct: declared permissions, granted states, AppOps, static APK endpoint references, and observed connection metadata are different facts. Static APK host references must never be presented as observed network traffic. A permission and an endpoint appearing together do not prove that data was transmitted. Do not present an averaged device-wide security score.

Preserve explicit authorization, VPN consent, destructive-action consequences, rollback state, and report-sharing privacy guidance. Icon-only controls retain accessibility labels. Support large system text and do not communicate state through color alone.

## Review gate

Review actual emulator screenshots for the application list, application detail, network, actions, settings, and overview. Include light mode, dark mode with enlarged text, and an expanded-window layout. Exercise navigation, selection, expansion, authorization, and rollback flows. A successful build alone does not establish visual quality.
