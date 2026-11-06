# Isolated Android28 native integration lab

Run from the conversation workspace after restoring the normal SDK/JDK build setup:

1. `python3 GRAPHITE_VPN/xray-tunnel/lab/prepare.py`
2. `python3 GRAPHITE_VPN/xray-tunnel/lab/run.py` (headless emulator boots; this process owns its ADB namespace and waits for the APK gate).
3. After a successful app/test APK build, copy candidate app to `/tmp/graphite-xray-ready.apk`, test APK to `/tmp/graphite-xray-test.apk`, then create `/tmp/graphite-xray-apk-ready`.

Old V0.2.7 APK must be available as output/GRAPHITE_VPN_V0.2.7-debug.apk or restored/WW/GRAPHITE_VPN_V0.2.7-debug.apk. The test APK must include gated UpdateSeedDeviceTest and XrayNativeLabDeviceTest. All tests require graphiteLocalFixture=true and ranchu/goldfish emulator hardware. Never run the fixture seeding flow on a real user's phone.

Only loopback/native test addresses are used. Public synthetic UUID11111111-1111-4111-8111-111111111111 is written to the guest /data/local/tmp/graphite-xray-uuid.txt. VLESS listens127.0.0.1:52401 and VMess127.0.0.1:52402. Their freedom outbound redirects virtual198.18.0.1:18080 to the tiny Go HTTP fixture on127.0.0.1:18080, returning GRAPHITE_XRAY_LAB_OK. No root TUN/netadmin/VPS/production credentials are required. The dummy legacy WG profile is used only to verify encrypted storage survival across adb install-r.

AVD userdata, Go compiler/cache and generated fixture configs are under /tmp. Large downloads are staged and hashed in /tmp before copying into toolchain; this avoids workspace-sync truncation. Emulator/SDK archives are verified against official repository SHA1, Go SDK against official SHA256, Xray standalone against its official GitHub release digest. Ubuntu libxkbfile is extracted into a scoped emulator-libs directory, not installed globally.

Logs: toolchain/device-xray.log and emulator-xray.log. On failure, the orchestrator keeps its emulator alive up to30minutes and accepts toolchain/xray-adb-request.json with {id,argv,timeout}, writing xray-adb-response-ID.log. A new exec's adb namespace must not be assumed to reach this emulator. Stop it with toolchain/stop-xray-device. On normal success/finalization, the whole emulator process group is terminated.

This is software emulation (no KVM), Android9/API28 x86_64. It does not prove physical Samsung Android15 performance, Reality/TLS/transport compatibility with every server, DNS leaks or FPS.

Xray26.10.31 denies private freedom targets by default for VLESS/VMess inbounds. The isolated fixture explicitly permits only TCP127.0.0.1/32:18080 in freedom finalRules before its redirect. This narrow local-server rule is not part of the app client configuration. Without it, the fixture blackholes even correctly tunneled requests.
