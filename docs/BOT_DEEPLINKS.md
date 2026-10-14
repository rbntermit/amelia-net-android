# Кнопки приложения → экраны бота

Плитки на главном экране открывают бота ссылкой `https://t.me/amelianet_bot?start=<payload>`.
Сейчас `bot.py` понимает только `pay` (и `ref<id>`), остальные открывают главное меню.
Чтобы каждая плитка вела на свой экран, в `start()` (около строки 12990) замените блок
«deep-link /start pay» на такой:

```python
    # deep-link /start <экран>: кнопки приложения Amelia и страница /me/
    _DEEP = {
        "pay": "pay",            # 💳 Тарифы и оплата
        "status": "svc_status",  # 🩺 Не работает? (когда VPN в приложении выключен)
        "help": "help",          # ❓ Помощь
        "support": "h_admin",    # 💬 Поддержка
        "referral": "referral",  # 🎁 Пригласить друга
        "mysub": "mysub",        # 📱 Моя подписка
    }
    try:
        parts = (m.text or "").split()
        if len(parts) > 1 and parts[1] in _DEEP:
            return _fake_cb(m, _DEEP[parts[1]])
    except Exception:
        pass
```

| Плитка в приложении | payload | callback в боте |
|---|---|---|
| 💳 Тарифы и оплата | `pay` | `pay` (уже работает) |
| 🎁 Пригласить друга | `referral` | `referral` |
| 🩺 Не работает? | `status` | `svc_status` — только когда VPN выключен; при включённом VPN приложение само проверяет интернет и подбирает рабочую локацию |
| 💬 Поддержка | `support` | `h_admin` |
| ❓ Помощь | `help` | `help` |
| 📢 Наша группа | — | открывает https://t.me/amelia_net |

Кнопка «Продлить» в карточке подписки (появляется за 3 дня до конца срока и после него) тоже ведёт на `pay`.
