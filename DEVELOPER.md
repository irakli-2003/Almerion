# Almerion — Developer Guide

This guide covers building and running the app from source, the command-line version,
configuration, the project layout and releasing. **Using** the desktop app is covered in the
[README](README.md).

---

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Build, test and run](#2-build-test-and-run)
3. [Command-line version](#3-command-line-version)
4. [Project structure](#4-project-structure)
5. [Data folders](#5-data-folders)
6. [Standard rules dataset](#6-standard-rules-dataset)
7. [Configuration](#7-configuration)
8. [Building the Windows installer](#8-building-the-windows-installer)
9. [Publishing a release and self-update](#9-publishing-a-release-and-self-update)
10. [Conventions](#10-conventions)
11. [Translations](#11-translations)
12. [Music](#12-music)

---

## 1. Prerequisites

- **JDK 21**, with `java` and `jpackage` on `PATH`.
- **Maven 3.8+**.
- **Git**.
- Windows is needed for the installer. The app itself runs anywhere JavaFX 21 does.

---

## 2. Build, test and run

```powershell
mvn test                          # run all tests
mvn package                       # build target\almerion-<version>.jar + target\libs\
mvn javafx:run                    # launch the desktop UI (com.dnd.ui.GuiApp)
java -jar target\almerion-<version>.jar   # launch through com.dnd.boot.Launcher
```

Handy variants:

```powershell
mvn -o test                                                         # offline, after one online build
mvn -o test "-Dtest=EntityFormTest" "-Dsurefire.failIfNoSpecifiedTests=false"   # one test class
mvn -o package -DskipTests
```

**Entry points**

| Class | Purpose |
|---|---|
| `com.dnd.ui.GuiApp` | JavaFX desktop app. Used by `mvn javafx:run`. |
| `com.dnd.boot.Launcher` | Jar manifest main class. Picks the newest verified downloaded update and starts the GUI. |
| `com.dnd.cli.CliApp` | Text-mode version (see below). |

---

## 3. Command-line version

The CLI is a minimal text front end over the same data. It's useful for quick checks and has no
accounts or maps.

```powershell
mvn -q compile
java -cp "target\classes;target\libs\*" com.dnd.cli.CliApp
```

(Run `mvn package` once first so `target\libs` exists.)

| Where | Command | Action |
|---|---|---|
| Landing page | `dm` / `player` | Choose a role |
| Campaign selection | `create` | Create a new campaign |
| Anywhere | `back` or `b` | Go back |
| Anywhere | `exit` | Quit |

---

## 4. Project structure

```
src/main/java/com/dnd/
├── boot/       Launcher: update selection and app start
├── update/     GitHub-release update checker and downloader
├── ui/         JavaFX app: GuiApp, SceneRouter, scenes/, components/, theme
├── cli/        Text-mode front end
├── auth/       Accounts, verification and reset codes, SMTP
├── cloud/      Firebase directory: accounts, memberships, join codes; CloudConfig
├── security/   Encryption for online sessions
├── music/      MusicLibrary, cues, AudioSource/AudioFiles (import, download cache), Attribution
├── ui/music/   MusicPlayer: JavaFX Media engine (crossfading music deck, ambience, effects)
├── ui/i18n/    UiTranslator, TranslationService, LanguagePicker
├── data/       AppHome (data root), repositories, campaign seeding
└── model/      Entities: characters, classes, races, items, spells, maps...
src/main/resources/
├── data/       default-campaign, custom-campaigns, srd, campaign-modes
├── i18n/       shipped UI dictionaries, one <code>.json per language
├── music/      starter-library.json (starter-pack URLs and attribution)
└── cloud.properties
tools/i18n/     extract_strings.py (catalogue of UI strings), translate.py (build-time translator)
packaging/      build-windows-installer.ps1, installer resources
Documentation/  rules, guides, catalogues, implementation notes
```

Useful components:

- `ui/components/EntityForm`: the generic entity editor. It is driven by reflection and draws
  ability grids, enum drop-downs, id-reference pickers and object lists.
- `ui/components/SearchPicker`: the searchable single- or multi-select picker.
- `ui/components/EntityHoverCard`: spell and item hover cards.
- `ui/print/CharacterSheetPdf`: PDF character sheets (PDFBox).

---

## 5. Data folders

`com.dnd.data.AppHome` resolves the data root:

| Situation | Data root |
|---|---|
| Installed app | `%APPDATA%\Almerion` |
| Running from source | The working directory (the repo root) |
| Overridden | `-Ddnd.home=<folder>` |

Under the root:

```
data\custom-campaigns\<campaign>\   catalogues (*.json), players.json, maps, storyline\, images\, music.json, notes.txt
data\auth\                          users.json, smtp-config.json, cloud.properties, directory-cache.json
data\player-profiles\               player-side portrait and appearance
data\joined-sessions\               last state of joined online sessions
images\                             image-picker library
app\                                downloaded update jars (installed app only)
```

On first start the installed app copies the bundled campaigns into `data\` without overwriting
anything. `data/auth/*` is git-ignored: never commit it.

The app was renamed from *DnD Campaign Manager*. `Launcher.userDataFolder()` moves an existing
`%APPDATA%\DnD Campaign Manager` to `%APPDATA%\Almerion` once (falling back to the old folder if
the move fails). The Java package (`com.dnd`), `-Ddnd.*` flags and the Firebase project id were kept unchanged.

The chosen UI language is kept in `data\settings.json`. Campaign audio is in
`<campaign>\music\` (imported files) and `<campaign>\music\cache\` (downloads, safe to delete).

### Personal campaigns are not shipped

`maven-jar-plugin` excludes `data/custom-campaigns/**` from the release jar: personal campaigns
contain images of unknown origin. Only `default-campaign` (original/SRD content) is seeded into
installs.

---

## 6. Standard rules dataset

Creating a campaign without **Start blank** seeds it from `src/main/resources/data/srd/`.

| Catalogue | Entries | Contents |
|---|---|---|
| `races.json` | 21 | Core ancestries plus their common subraces |
| `classes.json` | 12 | Hit die, primary abilities, saves, features and subclasses |
| `items.json` | 94 | 41 weapons (5 magic), 21 armours and shields, 12 potions and oils, 20 pieces of gear |
| `spells.json` | 67 | Cantrips to 9th level: school, components, range, cost |
| `monsters.json` | 59 | CR 0 to CR 24 |
| `beasts.json` | 26 | Animals and mounts by habitat |
| `npcs.json` | 22 | Townsfolk, patrons, villains |
| `places.json` | 18 | Most linked to a battle map |
| `maps.json` | 12 | Grid battle maps |
| `effects.json` | 34 | Conditions, including the six the damage engine looks up by id |
| `damage-types.json` | 15 | Physical and elemental |
| `languages.json` | 18 | Each with a primer book and study time |
| `books.json` | 26 | 18 primers plus 8 pieces of lore |
| `alchemy-ingredients.json` | 24 | Brewing reagents |
| `dice.json` | 7 | d4 to d100 |
| `players.json` | 6 | Pregenerated level 3 party |
| `id-registry.json` | — | Id bookkeeping |

New campaigns also get a starter `storyline/` with three session files. **Start blank** creates
empty versions of every catalogue.

The dataset is original text that describes rules concepts from the System Reference Document
(CC-BY-4.0). No SRD wording is reproduced verbatim.

Class features, the Artificer system and recipes are documented in
[`Documentation/implementation`](Documentation/implementation) and
[`Documentation/rules`](Documentation/rules).

---

## 7. Configuration

### JVM flags

| Flag | Effect |
|---|---|
| `-Ddnd.home=<folder>` | Use a different data root |
| `-Ddnd.update=off` | Disable the update check |
| `-Ddnd.cloud=off` | Keep accounts and memberships local, with no Firebase |
| `-Ddnd.cloud.<key>=<value>` | Override one `cloud.properties` key |

### Cloud directory (`cloud.properties`)

Defaults live in `src/main/resources/cloud.properties`. A per-machine override goes in
`<data root>\data\auth\cloud.properties`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` keeps everything local |
| `databaseUrl` | project Firebase RTDB | Realtime Database URL |
| `rootPath` | `sessions/dnd-directory` | Root node for accounts, memberships and join codes |
| `apiKey` | *(empty)* | Firebase Web API key. When set, and Email/Password sign-in is enabled, Firebase sends verification and reset emails. Not secret. |
| `authUrl` | `https://identitytoolkit.googleapis.com/v1` | Identity Toolkit endpoint |

### Email (`data\auth\smtp-config.json`)

This is used when Firebase email isn't configured. It is created with placeholders on first run:

```json
{
  "host": "smtp.gmail.com",
  "port": 587,
  "username": "you@gmail.com",
  "password": "an-app-password",
  "fromAddress": "you@gmail.com",
  "useTls": true,
  "enabled": true
}
```

- `host` must be a server such as `smtp.gmail.com`, not an email address.
- Gmail needs an [App Password](https://myaccount.google.com/apppasswords) (with 2-Step
  Verification on). Spaces in the password are removed automatically.
- Port `465` switches to implicit SSL. Office, university and VPN networks often block 587, 465
  and 25. No app setting can work around that.
- **Test email settings** on the login screen sends a real message and reports the exact
  error. When sending fails, the code is shown on screen instead.

### GitHub campaign sync

- Uses JGit (EDL-1.0) over HTTPS. Code: `com.dnd.github` (OAuth device flow, REST API, token
  store) and `com.dnd.sync` (`CampaignRepo` engine, `CampaignSyncService` background scheduler).
  UI: `ui/scenes/GitHubSyncUi`.
- **Client ID (one-time setup by the app owner):** GitHub → Settings → Developer settings →
  OAuth Apps → New OAuth App (homepage/callback URL can be `https://github.com`), then tick
  **Enable Device Flow**. Put the Client ID (public, not a secret) in `github.clientId` in
  `src/main/resources/cloud.properties`. Overrides: `data/auth/cloud.properties`, the
  `ALMERION_GITHUB_CLIENT_ID` environment variable or `-Ddnd.github.clientId=…`. No client
  secret is needed. If it is empty, the app falls back to a pasted fine-grained token. Scope: `repo`.
- The token is stored per OS user in `data/github/account.json` (DPAPI-encrypted on Windows;
  plain text with owner-only permissions elsewhere), is git-ignored and never written to
  campaign folders.
- Per-campaign settings live in the campaign's own `.git/config` (`remote.origin.url`,
  `almerion.sync`). Commits are authored as `<login> <id+login@users.noreply.github.com>`.
- Conflicts: merge; for conflicted files keep the local version, save the remote one as
  `<file>.remote-yyyyMMdd-HHmmss`, commit the merge and push.
- Running from source puts campaign repos (nested `.git`) under
  `src/main/resources/data/custom-campaigns`. Maven skips `.git`, but don't commit those folders
  to the app repo.
- JGit logs a harmless "No SLF4J providers" warning.

---

## 8. Building the Windows installer

```powershell
.\packaging\build-windows-installer.ps1              # tests + jar + EXE installer
.\packaging\build-windows-installer.ps1 -SkipTests
.\packaging\build-windows-installer.ps1 -Type app-image   # portable folder, no WiX needed
```

- Needs JDK 21 (`jpackage`) and Maven on `PATH`. WiX Toolset 3.14 is downloaded automatically to
  `%LOCALAPPDATA%\wix314` if it's missing.
- Output: `target\installer\Almerion-<version>.exe` and
  `almerion.jar` (the update payload).
- The installer bundles a Java runtime, runs `com.dnd.boot.Launcher` with up to 2 GB of heap, and
  adds a Start-menu group and an optional desktop shortcut.
- **Never change the installer's upgrade UUID.** Windows relies on it to upgrade in place.

---

## 9. Publishing a release and self-update

1. Bump `<version>` in `pom.xml`.
2. Build the installer (see above).
3. Create a GitHub release tagged `v<version>` and attach **both** the `.exe` and
   `almerion.jar`.

Installed apps check the latest release on start and ignore drafts and pre-releases.

- If only the jar is needed, they download it into `app\`, verify it, and switch on restart.
- Old installs look for the asset by its old name, find none, and are offered the installer.
  The installer keeps the same upgrade UUID, so it replaces the old install in place.
- If the release has no jar, or the new jar needs libraries the installed app doesn't have (from
  its manifest `Class-Path`), they ask the user to run the new installer instead.
- Running from source never updates.

---

## 10. Conventions

- Java 21, JUnit 4. Tests live in `src/test/java`, and UI tests start the JavaFX toolkit headless.
- Java sources use **CRLF** line endings.
- Keep entity JSON backwards-compatible: unknown or deleted ids are shown as `(missing)` and are
  never dropped.
- Never commit `data/auth/*`, personal campaigns or secrets.
- Product text says **Game Master (GM)**, never another publisher's trademarks. Bundled content
  must be SRD 5.1/5.2 (CC-BY-4.0) or original. Storyline notes use `[GM NOTE]`; `[DM NOTE]` is still
  read for older files.
- UI text is English in code. Mark user-authored text nodes with `UiTranslator.skip(node)` so
  they are never translated.
- Third-party material must be listed in `THIRD-PARTY-NOTICES.md`. Libraries must allow
  commercial use (Apache, MIT, BSD, EPL/EDL, GPL with Classpath Exception).

---

## 11. Translations

The UI is translated at runtime by `UiTranslator`, which walks every window and swaps displayed
text using the dictionaries in `src/main/resources/i18n/<code>.json`. There is no online
translation service. `Language` lists only languages whose dictionary is present.

Dictionary keys are the English text, in two forms:

- **Exact:** digits become `{0}`, `{1}`… (`"Level {0}": "Stufe {0}"`).
- **Patterns** for text built with `+` or `String.format`: each variable part is `{A}`, `{B}`…
  (`"Needs {A} XP (has {B})"`). Captured values are translated too when they are known strings.

Missing keys fall back to English, and a translation that drops a placeholder is ignored
(`TranslationServiceTest` checks every shipped file).

### How the dictionaries are made

Translation happens **at build time, never in the app**. `tools/i18n/translate.py` uses
[Argos Translate](https://github.com/argosopentech/argos-translate) (MIT licence, models
MIT/CC0), which runs fully offline on your machine. Only the finished JSON files ship; the
translator and its models are not part of the app or the installer.

- The installer script runs it automatically before Maven (skip with `-SkipTranslations`).
- It is incremental: existing translations are kept, only new strings are translated, and
  strings that no longer exist in the code are dropped. `--force` re-translates everything.
- Placeholders are protected. If the model still loses one, the string stays English.
- **Fixes:** put corrected translations in `tools/i18n/overrides/<code>.json`
  (`{"Apply": "Anwenden"}`). Overrides always win and survive re-translation.
- **Georgian (`ka`)** has no Argos model, so its file is maintained by hand/AI. The tool never
  writes it; `--check` reports what it is missing.

```powershell
python -m venv tools\i18n\.venv                                  # first time only
tools\i18n\.venv\Scripts\pip install -r tools\i18n\requirements.txt
$env:PYTHONIOENCODING = "utf-8"
tools\i18n\.venv\Scripts\python tools\i18n\translate.py              # all languages (~3 min each first time)
tools\i18n\.venv\Scripts\python tools\i18n\translate.py --lang de,fr # only some
tools\i18n\.venv\Scripts\python tools\i18n\translate.py --check      # report missing strings
python tools\i18n\extract_strings.py --missing ka                     # keys Georgian still lacks
```

The first run downloads each language model (~100 MB) to `%USERPROFILE%\.local\share\argos-translate`.

**Adding a language:** add its row to `Language.build()` (code, English name, native name), add
its Argos code to `ARGOS` in `tools/i18n/translate.py` (or `MAINTAINED_BY_HAND` if Argos lacks
it), run the translator, and list it in `TranslationServiceTest.allPlannedLanguagesShip`.
Right-to-left languages also go in `Language.RIGHT_TO_LEFT`.

---

## 12. Music

`MusicPlayer` uses JavaFX Media (`javafx-media`), which plays MP3, WAV, AIFF and M4A/AAC (not
OGG/FLAC). Tracks are either files copied into the campaign or direct audio URLs downloaded once
to `music/cache/<sha256>.<ext>`. Web-page links (YouTube, Spotify, ...) are rejected by
`AudioSource`, and legacy YouTube entries load as "unsupported".

Starter-pack audio (`resources/music/starter-library.json`) must be **CC0 or CC BY** only, with
every attribution field filled in; `MusicTest` enforces the license allow-list. Never add
NC/ND-licensed audio, Pixabay, Tabletop Audio or YouTube Audio Library material. List new
credits in `THIRD-PARTY-NOTICES.md`. Before a commercial release, consider mirroring the
starter files on your own release assets so third-party links can't break.

The installer has no browser engine any more; jpackage picks up `javafx-media-*-win.jar` from
`target\libs`. The self-updater refuses jars needing new libraries, so this release must be
installed with the new installer once.
