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
- Ядра — в `deps.env`: `LIBV2RAY_TAG` (Xray-core, релизы [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/releases))
  и `HEV_TAG` ([hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel/releases)). Их читают и сборка в CI, и
  `build.gradle.kts` (версии видны в «О программе»). hysteria2 отдельно не собирается: его реализует Xray-core.
- Раз в неделю workflow `deps-check` сравнивает их с последними релизами и, если есть новее, открывает
  pull request с новыми версиями и запускает на его ветке сборку APK (если pull request открыть нельзя — issue).
  Релиз он не выпускает.
- `applicationId = com.ameliajustamelia.vpn` менять нельзя после первого выпуска.

## Правила для ядер

- **Ядро в приложении не старше серверного.** Версия Xray-core в `deps.env` не ниже, чем на серверах
  (`xray version` на каждом сервере). Сначала выходит приложение с новым ядром, потом обновляются серверы.
- **Новую функцию протокола на серверах включаем только после того, как вышла версия приложения с
  ядром, которое её понимает** (и люди успели обновиться: смотрите версии в User-Agent у бота).
- **Старые версии отсекаются через `minVersionCode`** в `version.json` на сервере: приложение ниже этой
  версии не подключается и сразу предлагает обновиться (см. RELEASING.md).
- **Контракт report/probe** — ниже; меняется только по согласованию с серверной частью.

## Контракт report/probe

Приложение отправляет POST на те же адреса, что и подписка, по порядку:
`https://msk1.ameliajustamelia.com:9443`, `https://msk2.ameliajustamelia.com:9443`,
`https://sub.ameliajustamelia.com:9443`. Тело — JSON, `Content-Type: application/json`, не больше 64 КБ.
Сначала через обычную сеть телефона мимо VPN (`"path": "direct"`), если не вышло — через туннель
(`"path": "tunnel"`). User-Agent: `AmeliaNet/<versionName> v2rayNG/<upstream> Xray/<версия ядра>`
(начало `AmeliaNet/<версия>` не меняется: по нему бот считает установки).

**POST /app/report** — кнопка «Не работает — сообщить»:

```json
{"key": "<sub_id подписки>", "ts": 1700000000, "app": "1.1.0", "code": 7, "xray": "26.10.10",
 "hy2": "xray-26.10.10", "android": "14", "device": "Xiaomi 23053RN02Y", "tv": false,
 "net": "mobile", "op": "MTS RUS", "path": "direct", "active": "<имя сервера, на котором был>",
 "bypass_ru": true, "comment": "<до 500 символов>",
 "servers": [{"name": "Нидерланды 1", "host": "79.137.192.178", "port": 2079, "ok": false, "ms": null,
              "stage": "connect", "err": "<до 200 символов>"}]}
```

С 1.2.0 в отчёте ещё два поля: `"check_ms": 9400` — сколько заняла проверка всех серверов, мс; `"events"` —
до 20 последних автоматических переключений `[{"ts": <unix>, "from": "Нидерланды 1 🇳🇱", "to": "Финляндия 2 🇫🇮",
"why": "нет ответа 2 раза · 9 с"}]` (в `why` после « · » — за сколько секунд нашёлся рабочий сервер).

С 1.5.4 ещё три поля: `"vpn": true` — был ли включён VPN при отправке; `"sub_active": true` — действует ли подписка
(`expire` 0 или впереди); `"journal"` — до 20 последних строк «Журнала» (`"22.10.2026 14:05 Нет интернета"`, до 160
символов, старые первыми). Ключ остаётся в `"key"` (по нему бот находит подписку и отвечает), в тексте для бота его нет.

С 1.7.1 — необязательное поле `"play_protect"`, только если Google Play Защита не дала установить обновление и эта
версия ещё не установлена: `{"code": 28, "at": <unix>, "count": 2, "status": 3, "msg": "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed"}`
(`code` — какую версию не дали поставить, `at` — когда последний раз, `count` — сколько раз). Без блокировки поля нет.

Ответ: `{"ok": true, "id": "R1234"}`. `net`: `mobile` | `wifi` | `other`. `stage`: `connect` — к
серверу не соединиться; `data` — соединение есть, но запрос через сервер не проходит; `ok`.
`hy2`: hysteria2 встроен в Xray-core, поэтому здесь `xray-<версия Xray-core>`.

**POST /app/probe** — фоновые проверки для карты блокировок (переключатель «Помогать находить
блокировки», включён по умолчанию):

```json
{"iid": "<случайный id установки>", "ts": 1700000000, "app": "1.1.0", "net": "mobile",
 "op": "MTS RUS", "path": "direct",
 "servers": [{"host": "79.137.192.178", "port": 2079, "ok": true, "ms": 230, "stage": "ok"}]}
```

Ответ: `{"ok": true}`, ошибки не показываются. Без имён серверов, ключа, UUID, номера телефона и
сайтов. Отправка: не чаще раза в 6 часов на одну сеть; через 10 минут, если сервер, который работал,
перестал (не чаще раза в час); один раз при смене сети (Wi-Fi ↔ мобильная, другой оператор).

**GET /app/hints** (с 1.2.0) — что сейчас не работает у провайдера того, кто спрашивает. Те же адреса и порядок,
только мимо туннеля, без ключа (сервер считает по AS). Приложение спрашивает при запуске, при смене сети и раз в
30 минут, ответ хранит 30 минут, ошибки игнорирует:

```json
{"asn": 8359, "as": "MTS PJSC", "cc": "RU",
 "bad": [{"host": "79.137.192.178", "port": 2079, "bad": 3, "of": 4, "stage": "data"}], "ts": 1700000000}
```

Серверы из `bad` уходят в конец очереди автовыбора, если собственная проверка не показала, что они работают.

**POST /app/crash** (с 1.3.0) — отчёты о сбоях, переключатель «Отправлять отчёты о сбоях» (включён по умолчанию). Падения
во всех процессах и нефатальные ошибки (`where`: `core_start`, `vpn_service`, `update`, `update_blocked`, с 1.5.3 `update_stuck`, с 1.5.4 `boot_start`, с 1.5.5 `update_cancel` — не чаще раза в сутки) сохраняются в файл и
уходят при следующем запуске, мимо туннеля, не больше 5 в час:

```json
{"iid": "<тот же id установки>", "ts": 1700000000, "app": "1.3.0", "code": 11, "xray": "26.10.10",
 "android": "17", "device": "samsung SM-S731B", "thread": "main", "where": "", "fatal": true,
 "stack": "process: com.ameliajustamelia.vpn:daemon
…"}
```

Из стека перед отправкой удаляются ключ подписки (и любые 16 hex), UUID, ссылки `vless://`, `hysteria2://`,
`trojan://` и т. п., адреса `…/sub/…` и IPv4, кроме адресов наших серверов. Первая строка стека — процесс.

**version.json** с 1.3.0 содержит `abis` — APK под arm64-v8a (около 31 МБ вместо 75), только имя файла: сервер кладёт
его рядом с universal в `/app/`, приложение берёт адрес оттуда же, откуда пришёл version.json, и проверяет
`sha256` из `abis`. `url` и universal остаются для старых версий и бота.

## Локальная сборка

Нужны JDK 21 и Android SDK (platform 37, build-tools 37.0.0). Положить
[`libv2ray.aar`](https://github.com/2dust/AndroidLibXrayLite/releases) в `V2rayNG/app/libs/`, затем:

```bash
cd V2rayNG
./gradlew assemblePlaystoreDebug
```

Без `libhev-socks5-tunnel.so` (собирается в CI скриптом `compile-hevtun.sh`, нужен Android NDK)
приложение соберётся, но VPN-режим работать не будет.
