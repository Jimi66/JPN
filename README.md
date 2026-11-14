# JPN

JPN is an Android application project being developed from the requirements discussed with the project owner.

## Current implementation stage

The Android project now builds the native **Xray-core** engine through the official [XTLS/libXray](https://github.com/XTLS/libXray) wrapper and bundles its Android AAR into the APK.

The project includes an Android `VpnService` implementation that:
- establishes an Android TUN interface;
- supplies its file descriptor to Xray through `env.xray.tun.fd`;
- protects Xray's outbound sockets from being routed back into its own VPN tunnel;
- starts and stops Xray through the libXray API.

The service entry point is `com.jpn.app.vpn.XrayVpnService`. It accepts an Xray JSON configuration in the `xray_json` intent extra. The caller must first obtain the system VPN consent using `VpnService.prepare()`.

**This is not yet the complete JPN client.** Subscription URL fetching, parsing subscription responses into a complete Xray configuration, the final connection screen, DNS/routing UI, per-app selection, geo rules and server ping UI remain to be implemented and tested. The current bootstrap screen does not yet expose a Connect button.

## Build

GitHub Actions builds the pinned libXray Android AAR first, then builds the JPN debug APK.

After a successful run, download the `JPN-debug` artifact from the workflow run's Artifacts section.

## Technical baseline

- Kotlin
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- Go 1.27.3 for the native Xray build
- compileSdk 35
- minSdk 26
- Xray/libXray source pinned to commit `5a0f7d8d1c28b34afb5727b04b26a10f6dbac7e3`
