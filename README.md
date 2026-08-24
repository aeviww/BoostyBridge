# BoostyBridge

[![Version](https://img.shields.io/badge/version-r1.1-green)](#)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![CodeFactor](https://www.codefactor.io/repository/github/elytrya/boostybridge/badge)](https://www.codefactor.io/repository/github/elytrya/boostybridge)
[![Language](https://img.shields.io/badge/language-Java_17+-red)](#)
[![Platform](https://img.shields.io/badge/platform-Spigot_|_Paper_1.21+-green)](https://papermc.io/) <br>
[![GitHub latest commit](https://badgen.net/github/last-commit/Elytrya/BoostyBridge)](https://GitHub.com/Elytrya/BoostyBridge/commit/)
[![GitHub branches](https://badgen.net/github/branches/Elytrya/BoostyBridge)](https://github.com/Elytrya/BoostyBridge/)
[![GitHub commits](https://badgen.net/github/commits/Elytrya/BoostyBridge)](https://GitHub.com/Elytrya/BoostyBridge/commit/)
[![GitHub issues](https://badgen.net/github/issues/Elytrya/BoostyBridge/)](https://GitHub.com/Elytrya/BoostyBridge/issues/)
[![GitHub Downloads](https://img.shields.io/github/downloads/elytrya/boostybridge/total?&label=GitHub%20Downloads)](https://github.com/elytrya/boostybridge/releases)

[🇷🇺 Русский](#русский) | [🇬🇧 English](#english)

[![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/plugin/boostybridge)
[![Hangar](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/hangar_vector.svg)](https://hangar.papermc.io/Elytrya/BoostyBridge)
[![Spigot](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/spigot_vector.svg)](https://www.spigotmc.org/resources/boostybridge.133270/)

[![bStats](https://bstats.org/signatures/bukkit/boostybridge.svg)](https://bstats.org/plugin/bukkit/30011)

---

### Legal Disclaimer

**RU:** **BoostyBridge** никак не связан, не аффилирован, не спонсируется и не поддерживается платформой Boosty.to, её владельцами **Зайа Солюшнс Лимитед (Zaya Solutions Limited)**, или их официальными представителями. Все торговые марки и права принадлежат их законным владельцам.

**EN:**  **BoostyBridge** is in no way associated, affiliated, sponsored, endorsed, or officially connected with Boosty.to, its owners **Zaya Solutions Limited (Зайа Солюшнс Лимитед)**, or any of its subsidiaries or affiliates. All product and company names are trademarks of their respective holders.

---

## Русский

**BoostyBridge** — это open-source плагин для серверов Minecraft с интеграцией Boosty.to. Он автоматически выдаёт и снимает игровые привилегии в зависимости от подписки пользователя.

### Основные возможности

* **Верификация через личные сообщения бусти:** Подтверждение привязки через код, отправленный в личные сообщения Boosty.
* **Discord-бот:** Автоматическая синхронизация, выдача и снятие ролей на вашем Discord-сервере в соответствии с уровнем подписки.
* **Discord Webhook:** Отправка событий (подписка, отвязка, истечение) в Discord.
* **Автоматическая синхронизация:** Проверка подписок в фоне и авто-снятие прав.
* **PlaceholderAPI:** Интеграция с TAB, scoreboards и другими плагинами.
* **Админ команды:** Принудительная отвязка, привязка, синхорнизация.
* **Базы данных:** SQLite (по умолчанию) и MySQL.

### Плейсхолдеры

| Плейсхолдер | Описание |
| :--- | :--- |
| `%boosty_global_subscribers%` | Общее число подписчиков |
| `%boosty_level%` | Уровень подписки |
| `%boosty_name%` | Ник на Boosty |
| `%boosty_is_linked%` | Привязан ли аккаунт |
| `%boosty_has_sub%` | Есть ли подписка |

### Команды

| Команда | Описание | Права |
| :--- | :--- | :--- |
| `/boosty link <ник>` | Привязка Boosty | Все |
| `/boosty info` | Проверка статуса | Все |
| `/boosty discord <юзернейм>` | Привязка Discord-аккаунта к профилю | `boosty.discord` |
| `/boosty reload` | Перезагрузка | `boosty.admin` |
| `/boosty admin info <игрок>` | Информация об игроке | `boosty.admin` |
| `/boosty admin unlink <игрок>` | Отвязка | `boosty.admin` |
| `/boosty admin setdiscord <игрок> <дискорд>` | Ручное изменение привязанного Discord-ника | `boosty.admin.setdiscord` |
| `/boosty admin forcelink <игрок> <ник>` | Принудительная привязка | `boosty.admin` |
| `/boosty admin forcesync` | Принудительная синхронизация | `boosty.admin` |

### Верификация при привязке аккаунта бусти

Доступны несколько способов:

1. **Через личку бусти (основной)**
   - Пользователь получает код в личные сообщения
   - Вводит его на сервере

2. **Email (fallback)**
   - Если отправить сообщение в личные сообщения Boosty не удалось, пользователя попросят указать адрес электронной почты

### TODO

- [x] PlaceholderAPI
- [x] DM-верификация
- [x] Discord webhook
- [x] Ручная синхронизация <br>
(если появились идеи - создайте issues)

---

## English

**BoostyBridge** is an open-source Minecraft plugin with Boosty.to integration. It automatically grants and removes in-game perks depending on the user's subscription status.

### Main Features

* **Verification via Boosty direct messages:** Account linking confirmation using a code sent through Boosty DMs.
* **Discord bot:** Automatic synchronization, role granting, and role removal on your Discord server based on subscription level.
* **Discord Webhook:** Send events such as subscriptions, unlinks, and expirations directly to Discord.
* **Automatic synchronization:** Background subscription checks with automatic reward removal.
* **PlaceholderAPI:** Integration with TAB, scoreboards, and other plugins.
* **Admin commands:** Force unlinking, linking, and synchronization.
* **Databases:** SQLite (default) and MySQL.

### Placeholders

| Placeholder | Description |
| :--- | :--- |
| `%boosty_global_subscribers%` | Total subscribers |
| `%boosty_level%` | Subscription level |
| `%boosty_name%` | Boosty username |
| `%boosty_is_linked%` | Whether the account is linked |
| `%boosty_has_sub%` | Whether the user has an active subscription |

### Commands

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/boosty link <name>` | Link Boosty account | Everyone |
| `/boosty info` | Check account status | Everyone |
| `/boosty discord <username>` | Link Discord account to profile | `boosty.discord` |
| `/boosty reload` | Reload plugin configuration | `boosty.admin` |
| `/boosty admin info <player>` | View player information | `boosty.admin` |
| `/boosty admin unlink <player>` | Unlink account | `boosty.admin` |
| `/boosty admin setdiscord <player> <discord>` | Manually change linked Discord username | `boosty.admin.setdiscord` |
| `/boosty admin forcelink <player> <name>` | Force link account | `boosty.admin` |
| `/boosty admin forcesync` | Force synchronization | `boosty.admin` |

### Verification Methods

Several verification methods are available:

1. **Boosty direct messages (primary)**
   - The user receives a verification code in Boosty DMs
   - The code is entered on the server

2. **Email (fallback)**
   - If sending a Boosty DM fails, the user will be asked to provide an email address
   - The email is used as an alternative contact and verification method

### TODO

- [x] PlaceholderAPI
- [x] DM verification
- [x] Discord webhook
- [x] Manual synchronization command  
(If you have ideas — feel free to create an issue)

---

## Build

```bash
git clone https://github.com/Elytrya/BoostyBridge
cd BoostyBridge
mvn clean package
```
