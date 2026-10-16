# ❤️ Lifecore

A modern, lightweight and highly customizable **Minecraft 26.3 life system plugin** featuring Hardcore & Lifesteal gameplay modes, MySQL support, GUI interfaces, and a JSON-based multi-language system.

---

## ✨ Features

### 🎮 Game Modes

- **Hardcore Mode**
  - Players lose lives on death
  - Zero lives = ban

 - **Hardcore + Mode**
    - Players has 3 Lives
    - Zero lives = ban
   
---

### 💾 Storage System
- 🗄️ MySQL support (recommended for servers)
- 📁 File-based storage (YAML fallback)
- Fully configurable in `config.yml`

---

### 📊 UI & UX
- 🧡 Live ActionBar showing player lives
- 📦 Interactive GUI showing life status
- 🔒 Protected menus (no item stealing or drag exploits)

---

### 🌍 Multi-Language System
- JSON-based language files
- Player-specific language selection
- Easy to extend (DE / EN / more languages)
- Placeholder support for dynamic messages

---

## ⚙️ Commands

| Command | Description |
|--------|-------------|
| `/livesgui` | Opens the life GUI |
| `/mode hardcore / lifesteal`  | Changes game mode (admin only) |
| `/language download <es/it/fr>` | download the language | 
| `/langgui` | opens Language GUI |

---

## 🧠 Configuration

Example `config.yml`:

```yml
# =========================
# WRN LifePlugin Config
# =========================

# Standard Leben (für neue Spieler)
default-lives: 3

# Maximale Leben (für GUI Limit)
max-lives: 10

# =========================
# MODE SYSTEM
# =========================
mode:
  name: "Normal"
  lives: 3
  banOnZero: false

# =========================
# MARKETPLACE MODES
# =========================
marketplace:
  hardcore:
    name: "Hardcore"
    lives: 1
    banOnZero: true

  pro:
    name: "Pro"
    lives: 5
    banOnZero: false

  vanilla_plus:
    name: "Vanilla+"
    lives: 3
    banOnZero: false

# =========================
# PLAYER DATA (AUTO)
# =========================
lives: {}

# =========================
# SETTINGS
# =========================
settings:
  actionbar: true
  update-interval: 40  # ticks (20 = 1 Sekunde)
```

## Build and installation (Minecraft 26.3)

Requires Java 25 and Maven. Build with `mvn -B -ntp clean verify`; the shaded plugin is `target/lifeplugin-1.5.jar`. Install that JAR together with the current WRN-API build (`WRNapi/target/wrn-api-1.0.jar`) in the server `plugins/` directory, then start Minecraft 26.3 with Java 25. WRN-API is a required server plugin; it is not bundled in LifeCore. The obsolete unused `libs/wrn-api.jar` copy has been removed.

Challenge files are downloaded from `raw.githubusercontent.com/Felixwrn/lifecore-challenges` on startup. The default local YAML storage does not require MySQL or an OpenAI key.
