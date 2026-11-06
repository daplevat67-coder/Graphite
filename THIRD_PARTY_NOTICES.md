# Компоненты

Официальный AmneziaWG Android (commit ff15093bf3856fea0947923474870b86efb682f0) — Apache-2.0; AmneziaWG-Go/v3 v3.1.20260814 — MIT. Go1.25 runtime и x/crypto,x/net,x/sys — BSD. Полные тексты и attribution находятся в app/src/main/assets/licenses и входят в APK.

Upstream: https://github.com/amnezia-vpn/amneziawg-android и https://github.com/amnezia-vpn/amneziawg-go. Патчи интеграции и воспроизведение native-сборки — awg-tunnel/UPSTREAM.md. Обычный WG работает через тот же backend без маскировки. Остальные зависимости закреплены в Gradle.


ZXing Android Embedded 4.3.0 — JourneyApps, Apache-2.0, https://github.com/journeyapps/zxing-android-embedded. ZXing core 3.5.3 — ZXing authors, Apache-2.0, https://github.com/zxing/zxing. Full license: app/src/main/assets/licenses/ZXing-Apache-2.0.txt. No remote QR decoding service.


Xray integration: official XTLS/libXray v26.10.31, MIT; embedded Xray-core, MPL-2.0; heiher/hev-socks5-tunnel v2.19.3, MIT. Exact upstream source archives, release hashes, Java facade integration and reproducible fetch scripts are included in xray-tunnel/. All 51 transitive Go license notices and complete license texts are bundled under xray-tunnel/src/main/assets/licenses/ and included in the APK. See xray-tunnel/UPSTREAM.md and its license inventory for per-module authors, versions and licenses.
