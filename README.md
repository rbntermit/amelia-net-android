# Amelia.Net для Android

VPN-клиент сервиса Amelia.Net: вход по ключу из бота [@amelianet_bot](https://t.me/amelianet_bot),
список локаций и одна кнопка подключения. Ядро — [Xray-core](https://github.com/XTLS/Xray-core).

## Лицензия и происхождение

Amelia.Net — **производная работа от [v2rayNG](https://github.com/2dust/v2rayNG)** (© 2dust и участники
проекта), основана на версии 2.3.11. Как и оригинал, распространяется по лицензии
**GNU General Public License v3.0** — полный текст в [LICENSE](LICENSE).

- Исходный код каждой выпущенной сборки открыт в этом репозитории (тег `vX.Y.Z` = версия APK).
  Ссылка на него есть в приложении: меню → «О приложении».
- Изменения относительно v2rayNG: свой экран (`com.v2ray.ang.amelia`), applicationId, название
  и иконка, встроенная маршрутизация, отключённые экраны ручной работы с конфигами. Остальной код —
  v2rayNG без изменений; правки в его файлах помечены комментарием `Amelia.Net`.
- Сторонние компоненты: AndroidLibXrayLite и Xray-core, hev-socks5-tunnel, библиотеки AndroidX и
  др. — каждый под своей лицензией; список Java/Kotlin-зависимостей генерируется при сборке
  (`licensePlaystoreReleaseReport`).

## Как устроено

| Где | Что |
|---|---|
| `V2rayNG/app/src/main/java/com/v2ray/ang/amelia/` | Наш код: экран, ключ, подписка, маршрутизация |
| `AmeliaConfig.kt` | Адреса подписки (основной и запасной), бот, ссылка на исходники |
| `AmeliaRouting.kt` | Правила из HAPP_ROUTING: РФ напрямую, YouTube и остальное через VPN |
| `.github/workflows/build.yml` | Сборка и подпись APK |
| `docs/PLAN.md` | План, что есть в v2rayNG и что дописываем |

Гео-базы (только РФ) приложение берёт с `/geo/geoip.dat` и `/geo/geosite.dat` того же сервера
при первом запуске и раз в сутки. Обновления приложения — по `/app/version.json`: CI кладёт этот
файл в артефакт `AmeliaNet-universal` рядом с APK, оба нужно выложить на сервер в `/app/`
(другой адрес APK — переменная репозитория `APK_BASE_URL`).

Ключ = id подписки. Приложение собирает адрес `https://sub.ameliajustamelia.com:9443/sub/<ключ>`,
при недоступности пробует `sub.ameliajustamelia.net:9443`. Ссылка `amelia://key/<ключ>` открывает
приложение и сразу входит.

## Сборка в GitHub Actions

Каждый push в `main` собирает подписанные APK (артефакты `AmeliaNet-universal`, `-arm64-v8a`,
`-armeabi-v7a`). Тег `v1.0.0` дополнительно создаёт GitHub Release с APK и `SHA256SUMS.txt`.

### Подпись

Один раз создать ключ (храните `.jks` и пароли отдельно — без них обновления не встанут поверх):

```bash
keytool -genkeypair -v -keystore amelia-release.jks -alias amelia -keyalg RSA -keysize 4096 -validity 36500
```

Добавить секреты в репозиторий (Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `APP_KEYSTORE_BASE64` | `base64 -w0 amelia-release.jks` (в PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("amelia-release.jks"))`) |
| `APP_KEYSTORE_PASSWORD` | пароль хранилища |
| `APP_KEYSTORE_ALIAS` | `amelia` |
| `APP_KEY_PASSWORD` | пароль ключа |

### Версии

- `versionCode` / `versionName` — в `V2rayNG/app/build.gradle.kts`, поднимать при каждом релизе.
- Ядро Xray — `LIBV2RAY_TAG` в workflow (релизы [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/releases)).
- `applicationId = com.ameliajustamelia.vpn` менять нельзя после первого выпуска.

## Локальная сборка

Нужны JDK 21 и Android SDK (platform 37, build-tools 37.0.0). Положить
[`libv2ray.aar`](https://github.com/2dust/AndroidLibXrayLite/releases) в `V2rayNG/app/libs/`, затем:

```bash
cd V2rayNG
./gradlew assemblePlaystoreDebug
```

Без `libhev-socks5-tunnel.so` (собирается в CI скриптом `compile-hevtun.sh`, нужен Android NDK)
приложение соберётся, но VPN-режим работать не будет.
