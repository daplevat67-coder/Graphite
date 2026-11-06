# Official Android Xray data plane

Pinned **XTLS/libXray v26.10.31** Android ZIP SHA256 `fb99f04acf379cc2957c1e1d0ce2b3c0005d3f49974a44c09a11242a0562c1ca` and **heiher/hev-socks5-tunnel 2.19.3** AAR SHA256 `ab6c581e6157680cfe5687ee7bbdcf95405c006c2db3701879ec44b9e5f83836`. Official GitHub releases:

- https://github.com/XTLS/libXray/releases/tag/v26.10.31
- https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.3

The two binding JARs and four Xray libraries are unchanged official payloads. Four Hev libraries are rebuilt from the exact upstream 2.19.3 full source with NDK r27d and APP_PLATFORM=android-26. Upstream AAR targets Android29 and uses native TLS relocations unavailable on Android26–28; the rebuild uses compiler emulated TLS. No source patches are applied. `build_hev.py` verifies the source SHA256 and official Google NDK archive SHA1, preserves NDK ZIP symlinks, and records build metadata in `hev-build.json`. `fetch_native.py` reproduces official Xray/JAR payloads and this Hev build, verifies ten file hashes, and records ELF alignment and native Go build metadata. `verifyXrayNative` runs before Gradle build. All ELF LOAD alignments are at least 16 KiB. No nested-AAR packaging is used.

**Mandatory separate process:** libXray embeds Go1.27.3. AWG embeds a different Go runtime. Official libXray docs forbid two independently built Go runtimes in one process. Parent service must use `android:process=":xray"` and Android IPC. Java facade verifies the exact process before native calls. Never call libXray from the main/AWG process.

## API

`art.graphite.xray.XrayTunnelEngine(Context,VpnService)`:

- `start(String json, ParcelFileDescriptor original, String bootstrapDns, int mtu)`; shorter overloads default MTU1280 and DNS1.1.1.1:53.
- `isRunning()`, `getStats()` with public Snapshot.rxBytes/txBytes, `stop()`.

Parent owns the original PFD and closes it after stopping. Engine duplicates it. Official Hev borrows externally provided FD, does not close it; the facade closes the duplicate after HevStop joins its worker. Order: Hev → Xray → DNS reset → duplicate close → temporary YAML delete.

Xray JSON must contain a SOCKS inbound on 127.0.0.1. Facade injects random SOCKS user/password and matching private mode0600 Hev YAML. That file contains local tunnel information and ephemeral local credentials, never the remote UUID/server/TLS secrets. Full Xray config remains in memory. Xray logging is disabled, Hev logs go to /dev/null, and exceptions use fixed redacted codes. Official Xray DialerController invokes VpnService.protect(fd); SetDNS uses the physical network DNS captured before TUN creation. No root/firewall/sockopt marks are used.

Hev TUN addresses match parent service: 10.111.0.2 and fd42:6767::2. Pass actual MTU1280–1500. Both TCP and UDP relay use Xray SOCKS; fake ICMP echo is disabled. Native engine/interface UP is not proof of remote authentication or HTTP connectivity.

Hev JNI counters are `[txPackets,txBytes,rxPackets,rxBytes]`: tx counts reads from TUN (=app upload), rx writes back (=app download). Facade maps accordingly. On32-bit size_t counters maywrap; caller resets rate deltas on decreases. These count tunnel traffic and are not a throughput test.

## Sources and licenses

`fetch_licenses.py` downloads matching full upstream source archives, full libXray MIT/Xray-core MPL2/Hev MIT/Go BSD texts, Hev dependency licenses and linked-Go-module license texts derived from actual native-buildinfo.txt. `src/main/assets/licenses/` merges into the app. `upstream/` contains corresponding MPL-covered Xray-core source, libXray source, and full Hev release source (including submodules). The full Hev source is rebuilt by `build_hev.py`; only its Android target is overridden through the documented NDK command line. Original versions/hashes are not silently changed.

Reproduction: `python3 xray-tunnel/fetch_native.py`, then `python3 xray-tunnel/fetch_licenses.py`; build app with both modules. Existing AWG payload remains untouched. No physical-device throughput, leak or FPS claims are made here.

Repeated connections: Xray-core RegisterDialerController appends to a process-wide list; the facade installs exactly one static controller. A volatile active owner directs protection to the current VPN service, without retaining destroyed service closures. stop joins Hev and stops Xray before a new session; process reuse never loads the AWG runtime.
