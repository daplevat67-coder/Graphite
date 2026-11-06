# VLESS/VMess client profiles

`XrayProfileParser` reads a bounded individual share link (64 KiB), not a raw Xray JSON, subscription list, admin export or arbitrary routing code. The model and exceptions redact credentials. Xray itself implements transport security; this module does not implement encryption.

Supported transport names: TCP/raw, WebSocket, gRPC, XHTTP/splithttp, HTTPUpgrade. TLS certificate verification is always enabled. VLESS REALITY supports TCP/gRPC/XHTTP; Vision is restricted to TCP with TLS/REALITY. The REALITY pbk is normalized to a 32-byte public-key Base64URL value and emitted as current `realitySettings.password` (not a server private key). No arbitrary `extra` JSON is merged into the client config.

VMess accepts legacy Base64 JSON (`v=2`, `aid=0`) and current authority-style AEAD URI. Nonzero alterId, old ciphers and unsupported parameter/transport combinations fail explicitly. Public plaintext VLESS is rejected; private IP/localhost VLESS remains available for controlled isolated tests. VMess must use an authenticated cipher or TLS for public endpoints.

The generated config has one loopback SOCKS5 inbound (default 127.0.0.1:10808, UDP enabled), one proxy outbound, no direct/freedom fallback, disabled logging and outbound traffic counters for tag `proxy`. `serverAddress` optionally supplies a pre-resolved numeric address while retaining original TLS/REALITY serverName. The Android runtime must protect outgoing sockets and bootstrap endpoint DNS through its physical-network mechanism. Optional SOCKS credentials can be injected by the Android native module per session.

The original trimmed share link is stored/exported in the **same existing GVP1 AES-GCM envelope** used by native WG/AWG profiles. `.gwp`, UUID/AAD binding, directory, preferences and Keystore alias do not change. WG/AWG payloads retain the existing canonical config serialization. No schema migration or destructive storage rewrite is necessary.

`ProfileStore.config(id)` remains native WG/AWG-only. `xrayProfile(id)` returns the parsed model; `xrayConfig(id, socksPort, dnsServers, serverAddress)` generates JSON. `profile(id)` returns metadata without modifying selected UUID. `import`, `update` and explicit `export` accept either format. Proxy profile metadata intentionally does not claim TUN addresses/routes/DNS: the Android engine owns those actual settings.

Primary references inspected 2026-11-03:

- https://xtls.github.io/en/config/outbounds/vless.html
- https://xtls.github.io/en/config/outbounds/vmess.html
- https://xtls.github.io/en/config/transport.html
- https://xtls.github.io/en/config/transports/tls.html
- https://xtls.github.io/en/config/transports/reality.html
- https://github.com/XTLS/libXray (share-link formats)

Tests use synthetic UUIDs and documentation/private addresses only. New tests are supplied for the root's shared Gradle run; no server or private user profile is accessed by this agent.
