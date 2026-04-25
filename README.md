# ❤️ Lifecore

A modern, lightweight and highly customizable **Minecraft 1.21.x life system plugin** featuring Hardcore & Lifesteal gameplay modes, MySQL support, GUI interfaces, and a JSON-based multi-language system.

---

## ✨ Features

### 🎮 Game Modes
- **Hardcore Mode**
  - Players lose lives on death
  - Zero lives = kick (or elimination system)

- **Lifesteal Mode**
  - Players gain lives by killing other players
  - Configurable steal amount and maximum lives

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

---

## 🧠 Configuration

Example `config.yml`:

```yml
mode: LIFESTEAL

start-lives: 10

storage:
  type: FILE # or MYSQL

mysql:
  host: localhost
  port: 3306
  database: lifedb
  user: root
  password: password

lifesteal:
  steal-amount: 1
  max-lives: 20
