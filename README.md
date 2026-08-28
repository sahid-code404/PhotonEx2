# PhotonEx2 — Camera

PhotonEx2 is an Android **computational RAW-only** camera prototype. The current scope is deliberately focused: discover every app-accessible RAW lens, show only usable lenses, capture a dynamic RAW burst, reject poor frames, merge in the Bayer domain, and save one `.dng` file. There is no JPEG/HEIF still capture path in this project.

## Current implementation

- Public Camera2 discovery plus API 28+ logical multi-camera physical-member discovery.
- Opaque camera IDs remain internal; the UI presents canonical lens/zoom labels instead of vendor IDs.
- A lens is shown only when it advertises both a preview stream and `RAW_SENSOR` output.
- Physical/aux lenses are targeted through their logical parent using `OutputConfiguration.setPhysicalCameraId`.
- A single Camera2 owner controls device/session lifecycle and configures only preview + RAW_SENSOR surfaces.
- One shutter press captures a multi-frame RAW burst. Auto mode derives frame count from recent exposure time and ISO: bright scenes use fewer frames and progressively darker scenes use more, up to 12.
- Every discovered lens has its own persistent `Auto` or manual 2–12 frame policy.
- RAW images are immediately copied into bounded app-private RAW16 spools so `Image` objects are closed quickly.
- Merge stays in Bayer RAW: no JPEG, HEIF, YUV or demosaiced intermediate is created.
- Blur rejection uses a normalized high-frequency sharpness score. Accepted frames receive global alignment; shifts are constrained to even pixels so Bayer phase is preserved. Frames with excessive alignment error are rejected.
- Accepted samples are exposure-normalized and weighted into one RAW16 mosaic, then written as one `.dng` with `DngCreator`.
- Modern dark camera UI with rounded preview, lens pills, large shutter control, progress, and per-lens settings sheet.
- Development OTA: fixed development signer, CI-generated APK/manifest, rolling GitHub `dev-latest` prerelease, in-app version check, bounded download, SHA/package/version/signer verification, then Android's visible installer UI.

## Universality boundary

PhotonEx2 intentionally uses public Android camera APIs. It can enumerate public camera IDs and advertised physical members of logical multi-camera devices, including auxiliary lenses Android exposes to this app. It cannot make a vendor/system-only camera usable when the device HAL/framework withholds that route from third-party applications. A route may also advertise RAW but reject a physical preview+RAW session at runtime; the UI reports that failure instead of pretending the lens works.

## Build

Requirements: JDK 17, Android SDK 35/build-tools 35.0.0, Gradle 8.10.2.

```bash
bash scripts/prepare-dev-signing.sh
gradle testDevOtaUnitTest assembleDevOta
```

The development APK is produced at `app/build/outputs/apk/devOta/app-devOta.apk`. CI also uploads it as an artifact.

## Development OTA

Pushes to `develop` run the same tests/build verification and, only after a green build, publish `PhotonEx2-dev.apk` and `dev-manifest.json` under the rolling prerelease/tag `dev-latest`. The app checks this channel only after preview is available. The committed signing key is intentionally for development convenience and provides update continuity, not production authenticity. Do not use it for a future stable release.

## Reference policy

The project was designed after studying the separate CamX repository, especially its distinction between public and physical camera routes and its development-OTA continuity model. PhotonEx2 is an independent implementation: package names, models, engine, discovery, merge algorithm, UI, updater, workflow, and signing identity are its own; CamX source files were not copied into this repository.
