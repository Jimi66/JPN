# JPN

JPN is an Android application project being developed from the requirements discussed with the project owner.

## Current stage

This repository contains the initial Android build foundation and a GitHub Actions workflow that builds a **debug bootstrap APK**.

The current app is not yet a working VPN client. The VPN service, proxy core, subscription processing, DNS, routing rules, per-app proxy selection and server latency checks still need to be implemented and tested.

## Build

GitHub Actions builds the project on pushes to `main` and on manual workflow dispatch.

After a successful run, download the `JPN-debug` artifact from the workflow run's Artifacts section.

## Technical baseline

- Kotlin
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- compileSdk 35
- minSdk 26
