# Amelia.Net для Android

VPN-клиент сервиса Amelia.Net для Android и Android TV. Вход по ключу из бота
[@amelianet_bot](https://t.me/amelianet_bot), список локаций и одна кнопка подключения; приложение само
выбирает работающую локацию и переключается, если она перестала отвечать. Ядро —
[Xray-core](https://github.com/XTLS/Xray-core).

## Лицензия и происхождение

Amelia.Net — **производная работа от [v2rayNG](https://github.com/2dust/v2rayNG)** (© 2dust и участники
проекта), основана на версии 2.3.11. Как и оригинал, распространяется по лицензии
**GNU General Public License v3.0** — полный текст в [LICENSE](LICENSE).

- Изменения относительно v2rayNG: свой экран (`com.v2ray.ang.amelia`), applicationId, название
  и иконка, встроенная маршрутизация, отключённые экраны ручной работы с конфигами. Остальной код —
  v2rayNG без изменений; правки в его файлах помечены комментарием `Amelia.Net`.
- Сторонние компоненты: AndroidLibXrayLite и Xray-core, hev-socks5-tunnel, библиотеки AndroidX и
  др. — каждый под своей лицензией; список Java/Kotlin-зависимостей генерируется при сборке
  (`licensePlaystoreReleaseReport`).

## Где скачать

APK — в [GitHub Releases](https://github.com/rbntermit/amelia-net-android/releases). Тег `vX.Y.Z` = версия
APK, исходный код каждой выпущенной сборки — под этим тегом. Ссылка на исходники есть и в приложении:
меню → «О приложении».

## Как собрать самому

Нужны JDK 21 и Android SDK (версии — в `V2rayNG/app/build.gradle.kts`). Положить
[`libv2ray.aar`](https://github.com/2dust/AndroidLibXrayLite/releases) (версия — `LIBV2RAY_TAG` в `deps.env`)
в `V2rayNG/app/libs/`, затем:

```bash
cd V2rayNG
./gradlew assemblePlaystoreDebug
```

Без `libhev-socks5-tunnel.so` (собирается в CI скриптом `compile-hevtun.sh`, нужен Android NDK)
приложение соберётся, но VPN-режим работать не будет.

### Подпись

Релизы собирает и подписывает GitHub Actions. Секреты репозитория (Settings → Secrets and variables → Actions):
`APP_KEYSTORE_BASE64`, `APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS`, `APP_KEY_PASSWORD`.
