# JPN TURN socket protection

## Goal

The TURN Go client runs as a child process of JPN while JPN owns Android's
single `VpnService`. The TURN client's outbound sockets must bypass the JPN
TUN, but Xray and the rest of the JPN package must remain inside the VPN.

Excluding the whole `com.jpn.app` package with
`VpnService.Builder.addDisallowedApplication()` is therefore not acceptable.

## Android-side bridge

`TurnSocketProtector.kt` opens the Android abstract Unix-domain socket
`jpn.turn.protect` after `Builder.establish()` succeeds.

The Go child connects to the same endpoint using the Linux/Go abstract socket
notation `@jpn.turn.protect` and sends:

1. One data byte (`0x01`).
2. The newly-created network socket descriptor in an `SCM_RIGHTS` control
   message.

Android receives its own descriptor for the same kernel socket, duplicates it
as a `ParcelFileDescriptor`, calls `VpnService.protect(fd)`, and returns one
byte: `0x01` on success or `0x00` on failure.

The bridge validates the peer UID against JPN's UID before accepting a
descriptor. This matters because Android abstract local sockets do not have
filesystem permission checks.

## Go-side helper

The source templates in `tools/turn-core-patch/` implement the IPC request.
The Android helper deliberately fails closed if the environment variable or
the Android service is unavailable. Desktop/server builds use a no-op stub.

The child process must be launched with:

```text
JPN_TURN_PROTECT_SOCKET=@jpn.turn.protect
```

## Required Go-core integration points

The helper is not yet wired into the upstream Go core. Before using the core
inside JPN, install `turnSocketProtectControl` on every outbound dial path:

- `directNet.Dial`, `directNet.DialUDP`, and `directNet.DialTCP`.
- `directNet.CreateDialer` and its wrapped `net.Dialer.Control`.
- Direct UDP dials in `oneTurnConnection` and `createSmuxSession`.
- The custom dialer used by `tls-client` and its DNS resolver.
- The Gorilla WebSocket dialer used for VK Calls authentication.
- The custom `dnsdialer` path: its resolver sockets also need protection;
  the current dependency does not expose a dialer injection option in the
  constructor used by this client, so this path needs a focused replacement
  or dependency patch.

Local ingress listeners (`127.0.0.1:9000` and the manual-captcha HTTP listener)
must remain local and do not need VPN protection.

## Build target

The upstream core CI already builds Android clients with Go, Android NDK
r26d, API 21, `GOOS=android`, `GOARCH=arm64`, and `CGO_ENABLED=1`.
The Android artifact is an executable Go client (not a JNI library), launched
by `ProcessBuilder` from the app's native-library directory.

## Status

- Android FD-receive/protect bridge added to the JPN experimental branch.
- Go-side IPC helper templates added.
- The upstream Go core is not yet patched to use the helper.
- No TURN binary is bundled yet.
- No build or device-level socket-protection test has been run.

The next proof-of-concept must verify FD passing and `VpnService.protect()`
on a real Android device before routing any production TURN traffic through
this path.
