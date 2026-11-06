# QR and link payload subset

`ImportPayloadDecoder` performs offline classification/decoding and full native ProfileValidation or XrayProfileParser validation before returning a profile payload. No URL is fetched and no shell code/admin actions are performed. Every payload wrapper redacts its toString; ConfigPayload.config is caller-owned and must be wiped after confirmation/import/cancellation.

Supported:

- Individual `vless://` and `vmess://` share links, validated by XrayProfileParser. See domain/xray/README.md for supported transport/security combinations. The original trimmed link is retained for encrypted storage and explicit export.

- WireGuard/AmneziaWG native `.conf` text (the usual native WireGuard QR contents).
- `wireguard://BASE64` and `awg://BASE64`: **GRAPHITE extensions** encoding UTF-8 native `.conf` bytes with standard or URL-safe Base64. There is no claim that every URI published under these schemes is compatible.
- `https://...`: only classified as a remote configuration link for an explicit UI-controlled download. Credentials, fragments, invalid ports/control characters are rejected. Server response must separately be validated/imported.
- Amnezia `vpn://BASE64URL`: Qt qCompress envelope (4-byte big-endian uncompressed size followed by zlib) or plain Base64URL JSON. Only an existing, unambiguous native client config at `containers.{awg,wireguard}.last_config.config` is extracted. Legacy protocol keys `amnezia-awg`/`amnezia-wireguard` are also recognized. The selected `defaultContainer`, if present, is respected.
- Official Amnezia single-chunk QR framing: Base64URL QDataStream `qint16(1984), quint8(total=1), quint8(index=0), quint32(length), chunk`. Multi-frame QR is explicitly unsupported, rather than importing the first incomplete part.

Not supported: Amnezia full administrative exports, API/subscription access keys, containers without an embedded native client `.conf`, ambiguous multi-client envelopes, raw Xray JSON, Trojan and proxy subscription lists and encrypted vendor links. Never reconstruct client credentials from server/admin secrets.

Limits: input 256 Ki characters, native config 64 KiB, compressed input 64 KiB, inflated JSON 1 MiB, JSON nesting 32, at most 20 containers. UTF-8 is strict; the qCompress length and stream termination must match exactly. Tests use only synthetic documentation-address fixtures, no real keys.

Primary format references inspected on 2026-11-03:

- https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/controllers/selfhosted/importController.cpp
- https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/controllers/selfhosted/exportController.cpp
- https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/utils/qrCodeUtils.cpp
- https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/utils/qrCodeUtils.h
- https://github.com/amnezia-vpn/amnezia-client/blob/dev/client/core/utils/constants/configKeys.h

The format was implemented independently; these files are not copied into GRAPHITE.
