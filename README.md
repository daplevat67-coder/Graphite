# GRAPHITE VPN 0.3.1-alpha1

Android-клиент на Kotlin и экспериментальный Graphite Tunnel.

- `app/` — Android-клиент.
- `graphite-server/` — сервер Graphite Tunnel для ПК или VPS на Python, с инструкцией запуска.
- `awg-tunnel/` и `xray-tunnel/` — модули VPN, лицензии и контрольные суммы нативных библиотек.

Для сборки Android нужны JDK 17, Android SDK 35 и Gradle Wrapper. Готовые нативные библиотеки JNI находятся в полном архиве исходников GRAPHITE VPN 0.3.1, переданном вместе с APK. Скопируйте каталоги `awg-tunnel/src/main/jniLibs` и `xray-tunnel/src/main/jniLibs` из архива с сохранением путей. Для сборки APK с подписью обновления используйте свой закрытый ключ по инструкции в [SIGNING.md](SIGNING.md).

Graphite Tunnel использует TLS 1.3 для шифрования. Формат аутентификации и кадров экспериментальный.
