# AmneziaWG userspace engine

Official Android source: https://github.com/amnezia-vpn/amneziawg-android commit ff15093bf3856fea0947923474870b86efb682f0. Official Go module github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260814, pinned go.sum.

Included official config, crypto, userspace backend and JNI bridge. Unused RootShell, ToolsInstaller and kernel AwgQuickBackend are omitted. GRAPHITE uses Android VpnService without root. Plain WG profiles use the same engine with obfuscation disabled. No cryptographic implementation changes.

Integration patches: native status polling starts only with a non-null callback (GRAPHITE serializes statistics/cleanup itself); DNS search-only domains retained during serialization; JNI closes a newly created device on IpcSet failure and checks optional UAPI file before cleanup. IPC path linker symbol uses module /v3.

Native build: Go1.25.0 plus original upstream CLOCK_BOOTTIME patch, NDK26.1.10909125/API26, arm64-v8a/armeabi-v7a/x86_64/x86, 16KiB ELF alignment. Prebuilt JNI included; Gradle checks native-sha256.json. Rebuild with ANDROID_NDK_HOME and GRAPHITE_AWG_GO pointing to patched Go, then python3 scripts/build_awg_native.py.

Android module Apache-2.0 (COPYING/headers); AmneziaWG-Go MIT. See app/assets/licenses and THIRD_PARTY_NOTICES.md.
