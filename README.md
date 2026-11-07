# GRAPHITE VPN 0.3.5-alpha1

Android-клиент на Kotlin с подписками на серверы. Graphite Tunnel и Self-hosted временно отключены в приложении; сохранённые экспериментальные профили не удаляются.

Инструкция, поддерживаемые форматы и ограничения подписок: [SUBSCRIPTIONS.md](SUBSCRIPTIONS.md).

- `app/` — Android-клиент.
- `graphite-server/` — сервер Graphite Tunnel для ПК или VPS на Python, с инструкцией запуска.
- `awg-tunnel/` и `xray-tunnel/` — модули VPN, лицензии и контрольные суммы нативных библиотек.

Для сборки Android нужны JDK 17, Android SDK 35 и Gradle Wrapper. Готовые нативные библиотеки JNI находятся в полном архиве исходников GRAPHITE VPN 0.3.1, переданном вместе с APK. Скопируйте каталоги `awg-tunnel/src/main/jniLibs` и `xray-tunnel/src/main/jniLibs` из архива с сохранением путей. Для сборки APK с подписью обновления используйте свой закрытый ключ по инструкции в [SIGNING.md](SIGNING.md).

Серверная лаборатория Graphite Tunnel использует TLS 1.3. В текущий APK лабораторный сервер и SSH-установщик не включаются.
