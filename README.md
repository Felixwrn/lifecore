# LifeCore 2.1

A lives and game-mode plugin for **Minecraft 26.3**, **Java 25**, and Spigot/Paper.
Install the current **WRN-API** plugin alongside LifeCore.

## v2.1 features

- **Reworked MySQL storage:** Hikari connection pool, scoped JDBC resources, schema versioning, legacy `lives` table migration, and asynchronous serialized writes. Lives, language, challenge progress and completed challenges share one immutable player profile. Failed writes remain pending for retry; database failures never silently switch storage or create replacement players. Atomic YAML storage remains the default.
- **AI Mode Builder:** `/mode ai <id> <description>` converts text into a validated declarative mode using OpenAI. The result opens as a preview; save or activate it explicitly. AI cannot install code or execute commands.
- **GUI Mode Builder:** create/edit a mode without AI; adjust starting/max lives, death loss, life stealing, amount, elimination behavior, icon, name and description. Saved modes actually control the death/kill gameplay rules.
- **Online Marketplace:** fetch a configurable catalogue and download individual modes or packs. SHA256 verification, complete validation, no overwrite by default, and rollback protect existing local modes/challenges. Downloads contain data, not JARs or scripts.
- **Online Challenges:** configurable verified index, block/mob/craft objectives, persistent capped progress and one-time completion. Rewards are real Minecraft XP, not a displayed number.
- **Auto Update System:** periodic GitHub stable-release checks; an optional automatic download verifies a release-bound manifest and the actual plugin JAR before staging it in Bukkit's update directory. A server restart applies the staged update; the running plugin is never hot-swapped.
- **Online controls:** HTTPS host allowlist, bounded request/body timeouts, download limits, and background I/O. Online features can be disabled independently of local gameplay and the manual builder.

## Build and install

```sh
mvn -B -ntp clean verify
```

Use JDK 25. Install `target/lifeplugin-2.1.0.jar` and WRN-API into the server's
`plugins/` folder. MySQL connector and connection-pool dependencies are shaded
into the LifeCore JAR. No separate JDBC JAR is required.

The first start creates configuration, built-in mode definitions and a local
stone-mining challenge. New players receive the active mode's starting lives;
existing balances are preserved when changing modes. Increasing a balance stops
at the active mode's cap; a legacy balance above a new cap is not reduced as a
side effect of earning a life. Modes can eliminate players through a ban or
spectator mode.

## Commands

| Command | Purpose | Permission |
| --- | --- | --- |
| `/mode` or `/modegui` | Open the mode selector | `life.mode` |
| `/mode list` / `/mode set <id>` | List or activate a saved mode; console supported | `life.mode` |
| `/mode create <id>` / `/mode edit <id>` | Open the manual builder | `life.mode` |
| `/mode ai <id> <description>` | Generate a mode preview | `life.mode`, `life.ai` |
| `/livesgui` | Edit online player balances by UUID | `life.admin` |
| `/langgui` | Persist the player's DE/EN selection | `life.language` |
| `/market` / `/market refresh` | Browse or refresh the catalogue | `life.market` |
| `/market install <id> [--replace]` | Install a catalogue item; replacement is explicit | `life.market` |
| `/challenge` | Show personal objectives/progress | `life.challenge` |
| `/challenge refresh` | Fetch missing online challenge definitions | `life.challenge`, `life.online` |
| `/lifecore status` / `/lifecore flush` | Service status or acknowledged data flush | `life.admin` |
| `/lifecore update [check\|download]` | Check or stage a verified update | `life.admin`, `life.update` |

Admin permissions default to operators; language and challenge access default to
all players. `life.admin` includes mode, AI, marketplace, online-refresh and
update permissions. Menus check both their owner and current permissions on
every action, protect top/bottom inventories and drag operations, and defer
inventory changes to the next server tick. Chat prompts expire, accept `cancel`
or `/cancel`, and are removed on disconnect or shutdown.

## Storage configuration and upgrades

`storage.type: FILE` uses versioned `players.yml`. `MYSQL` uses the configured
server/database with Hikari pooling. Configure `storage.mysql` in the server's
private LifeCore `config.yml`; the password can instead come from
`LIFECORE_MYSQL_PASSWORD` (or the name in `password-env`). Production defaults
use `ssl-mode: VERIFY_IDENTITY`; configure the database CA/trust as required by
Connector/J. No credentials belong in this repository or in issue reports.

On upgrade, missing profiles import legacy `config.yml` lives/challenge progress
and the old `lives.yml`; existing stored profiles take precedence. MySQL also
imports the old `lives(uuid,lives)` table only where a new player row is absent.
Legacy source files/tables are retained. Completed legacy challenge progress is
marked completed rather than awarding an old objective again. Back up these
sources before an upgrade. Switching between FILE and MYSQL is an operator
migration task; selecting a different backend does not copy all data from the
previous backend automatically.

Player data is loaded before login on an asynchronous login thread. New logins
are deferred if initialization/storage is unavailable. Reads during gameplay use
the cache; mutations queue serialized writes. Disconnect, periodic flush and
shutdown flush pending snapshots. This does not replace database/backups or
protect against abrupt host/power loss before a write is acknowledged.

## AI configuration

Enable `ai.enabled` and provide `LIFECORE_OPENAI_API_KEY`, or a key in the server's
private `ai.api-key` setting. `api-key-env`, `model`, `endpoint` and timeout are
configurable. The default endpoint is OpenAI Chat Completions with
`gpt-4.1-mini`. No key is bundled; missing configuration leaves manual mode
creation available. AI responses must match the supported JSON schema and are
validated with the same rules used by the manual builder and marketplace.

Only an explicit admin request invokes the paid provider. Development tests use
local HTTP fixtures; they do not validate a live account, model entitlement or
billing. An existing mode can be replaced only by explicitly saving its preview.

## Online catalogue and challenges

The default catalogue is `online/catalog.json` in this repository on `main`;
its sample starter pack includes a Lifesteal mode and a stone-mining challenge.
The separate challenge index is `online/challenges/index.json`. These URLs become
available after this feature branch is merged into `main`; when testing a PR,
use a catalogue/index whose download URLs also target the PR branch (changing
only the index URL does not rewrite its payload URLs). Both URLs and the
allowed HTTPS hosts are configurable. Existing local challenge definitions
are retained during automatic refresh.

Catalogue format:

```json
{"schemaVersion":1,"entries":[{"id":"starter","name":"Starter pack",
  "description":"Modes and challenges","type":"pack","version":"1.0.0",
  "url":"https://raw.githubusercontent.com/OWNER/REPO/main/online/pack.json",
  "sha256":"SHA256_OF_EXACT_DOWNLOAD_BYTES"}]}
```

Pack payloads contain `schemaVersion: 1`, a `modes` array and a `challenges`
array. Challenge entries can be validated objects or YAML strings. Supported
challenge fields are `id`, `name`, `type`, `target`, `goal`, `reward` (XP).
Events are `BREAK_BLOCK`, `KILL_MOB`, `CRAFT_ITEM`; the old `Break Stone` format
still works. Mode fields are documented in [the example pack](online/packs/lifesteal-starter.json).
IDs are bounded lowercase filenames, content is strictly parsed and the entire
pack is validated before installation. See the checked-in sample indexes for
exact schemas and matching hashes. Remote changes require refreshing the digest.

Challenge completion is persisted before XP is scheduled on the main thread.
Completion is at most once, but XP delivery is not transactional with Minecraft:
a crash, disconnect, plugin shutdown or failed persistence can withhold the XP.
It is not automatically paid again after such a failure. Live craft/block/mob
counts and GUI appearance should also be checked with test players.

## Verified automatic updates

Checks run on startup and periodically when `online.enabled` and update checks
are configured. `updates.auto-download` defaults to **false**. Enabling it
stages compatible, newer stable releases automatically; otherwise use
`/lifecore update download`. Existing local plugin files are not overwritten
while the server is running.

A compatible stable GitHub release must attach both the final plugin JAR and
`lifecore-update.json`. The manifest binds its version, Minecraft `26.3`, exact
release-asset JAR URL and SHA256. The downloader also checks `plugin.yml`, main
class and archive limits; other plugins and prereleases are rejected.

Maintainers can create a manifest from the exact built JAR:

```sh
python3 tools/create-update-manifest.py target/lifeplugin-2.1.0.jar \
  --repository Felixwrn/lifecore --tag v2.1.0 \
  --output target/lifecore-update.json
```

The tag-triggered release workflow builds and publishes these two assets when a
maintainer pushes a matching stable version tag. The POM, plugin descriptor and
tag version must agree. No tag/release is created by a normal PR build.

## Validation

`mvn clean verify` runs mode/AI HTTP fixtures, catalogue/update HTTP+TLS and
rollback tests, data restart/error tests, challenge progress/reward scheduling,
menu access and chat lifecycle tests. To include the real MySQL/MariaDB test,
point `LIFECORE_TEST_MYSQL_URL`, `LIFECORE_TEST_MYSQL_USER` and
`LIFECORE_TEST_MYSQL_PASSWORD` at a **disposable test database**: the integration
test drops its fixture tables. GitHub Actions supplies an isolated MariaDB
service and executes this test. Application TLS verification remains enabled;
only the local/CI disposable database fixture uses plaintext JDBC.

These automated checks do not represent a production server load test, live AI
provider call or a manual multiplayer/GUI playthrough.
