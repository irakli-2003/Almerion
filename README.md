# Almerion: Tabletop RPG Campaign Manager

**A desktop companion for running fantasy tabletop role-playing campaigns.** The Game Master
prepares the world, writes session notes, runs battles on a grid map with music, and prints
character sheets. Players follow along from their own computers.

> This guide is for **people who use the app**. If you want to build it from source, change the
> code or use the command-line version, see the [Developer Guide](DEVELOPER.md).

---

## Contents

1. [What you can do with it](#1-what-you-can-do-with-it)
2. [System requirements](#2-system-requirements)
3. [Install, update and uninstall](#3-install-update-and-uninstall)
4. [Quick start for Game Masters](#4-quick-start-for-game-masters)
5. [Quick start for players](#5-quick-start-for-players)
6. [Key concepts](#6-key-concepts)
7. [Accounts and sign-in](#7-accounts-and-sign-in) · [Language](#7a-language)
8. [Campaigns and members](#8-campaigns-and-members) · [Sync with GitHub](#syncing-campaigns-between-computers-with-github)
9. [The GM menu](#9-the-gm-menu)
10. [Characters](#10-characters)
11. [Worldbuilding](#11-worldbuilding)
12. [Storyline and session notes](#12-storyline-and-session-notes)
13. [Maps and battles](#13-maps-and-battles)
14. [Music and sound](#14-music-and-sound)
15. [Online sessions](#15-online-sessions)
16. [Printing character sheets](#16-printing-character-sheets)
17. [Rules reference: class features and the Artificer](#17-rules-reference-class-features-and-the-artificer)
18. [Keyboard shortcuts](#18-keyboard-shortcuts)
19. [Your data and backups](#19-your-data-and-backups)
20. [Troubleshooting](#20-troubleshooting)
21. [FAQ](#21-faq)
22. [More documentation](#22-more-documentation)
23. [Legal notice](#23-legal-notice)

---

## 1. What you can do with it

| As a Game Master | As a player |
|---|---|
| Keep several campaigns, each with its own world, party and notes | Join a campaign with a short join code |
| Start from a ready-made rules set (races, classes, spells, items, monsters, maps) or from a blank world | Pick your character and see its stats, inventory and abilities |
| Create characters with a step-by-step wizard that explains every choice | Add your own portrait and appearance text |
| Write session notes with read-aloud passages, map links and music cues | Follow the GM's live session from your own computer |
| Run battles on a grid map: initiative, damage, healing, spells, ranges, rests | |
| Play your own music, ambience and sound effects that follow the action (free starter pack included) | |
| Show the battle map to the table on a second screen | |
| Print standard character sheets as PDFs | |
| Invite other GMs and players, and host a live online session | |

---

## 2. System requirements

- **Windows 10 or 11** (64-bit).
- About **1 GB of disk space** for the app, plus about 150 MB the first time you play music.
- **4 GB of RAM** or more is recommended.
- **An internet connection** for accounts, shared campaigns, online sessions, music and updates.
  Without one you can still open campaigns stored on your computer.
- A **second monitor or projector** is optional, for showing the battle map to players.

You don't need to install Java or anything else. The installer includes everything.

---

## 3. Install, update and uninstall

### Install

1. Open the [latest release](https://github.com/irakli-2003/Almerion/releases/latest).
2. Download **`Almerion-<version>.exe`**.
3. Run it and follow the setup wizard. You can choose the install folder and whether to add a
   desktop shortcut.
4. Start **Almerion** from the Start menu or the desktop shortcut.

> Windows SmartScreen may warn that the installer is from an unknown publisher. Click
> **More info → Run anyway**.

### Update

The app checks for a new version each time it starts.

- When one is available you see **Update available**. Click **Download update** to download it in
  the background while you keep working, or **Later** to skip it this time.
- When it's ready, choose **Restart now**, or **Next time** to switch to it automatically the next
  time you open the app.
- Occasionally an update needs the full installer. The app then shows **Version X needs the
  installer**, and **Open download page** takes you to it. Installing over the old version keeps
  all your campaigns.

### Uninstall

Use **Windows Settings → Apps → Installed apps → Almerion → Uninstall**.
Your campaigns and settings are **not** deleted. They stay in your data folder (see
[Your data and backups](#19-your-data-and-backups)), so a later reinstall picks them up again.

---

## 4. Quick start for Game Masters

This walkthrough takes about ten minutes and gets you from installing the app to running a first
fight.

1. **Create an account.** On the login screen, click **Create an account**. Enter a username,
   email and password (at least 6 characters), then verify your email (see
   [Accounts and sign-in](#7-accounts-and-sign-in)).
2. **Choose your role.** After logging in, click **Game Master**.
3. **Create a campaign.** Click **Create New Campaign** and type a name. Leave
   **Start blank** *unticked* to get a complete starter world (races, classes, spells, items,
   monsters, maps and a pregenerated party). Click **Create Campaign**.
4. **Open it.** Double-click the campaign, or select it and click **Open Campaign**. The
   [GM menu](#9-the-gm-menu) opens.
5. **Add a character.** In **Players**, click **Create Character** and follow the eight steps.
   Every option explains itself as you pick it.
6. **Write a session.** Click **📜 Open Storyline**, then **New File**. Write what happens. Use
   **Read-Aloud** for text you will read to players and **Insert Map** to link a battle map.
7. **Run a battle.** Double-click the map link in your notes (or open **Maps** in
   Worldbuilding, then **View Map → Run Battle**). Add tokens, click **Roll Initiative**, and use
   **Next Turn** to step through the fight.
8. **Invite your players.** Click **👥 Members** and share the **join code**. Accept each player
   when their application arrives.

---

## 5. Quick start for players

1. **Create an account** and log in (see [Accounts and sign-in](#7-accounts-and-sign-in)).
2. Click **Player**. The **My Campaigns** screen opens.
3. Under **Join a Campaign**, type the join code your GM gave you (for example `K7QM2X`) and
   click **Apply**. The campaign appears under *Waiting for a GM to accept you*.
4. Once the GM accepts you, the campaign is listed with your role. When the GM is running a live
   session, click **Join Live Session**.
5. Choose your character and click **Play This Character**. If the GM protected it with a
   password, enter that first.
6. From your character's home screen you can **View Inventory**, **View Abilities**, open
   **My Profile** to upload a portrait and write your appearance, and return to the
   **Online Session**.

Your GM controls stats, level, items and spells. Your portrait, name and appearance text are
kept on your own computer.

---

## 6. Key concepts

| Term | Meaning |
|---|---|
| **Campaign** | One world: its characters, catalogues (monsters, items, spells...), maps, session notes and music. |
| **Author GM** | The person who created the campaign. Only they can delete it, and add or remove other GMs. |
| **GM** | A co-Game Master. Can open and edit the campaign. |
| **Player** | A member who plays a character in the campaign. |
| **Join code** | A short code (like `K7QM2X`) that lets someone *apply* to join a campaign. A GM still has to accept them. |
| **Catalogue** | A list of things in the campaign, such as Monsters, Items or Spells. Edited from **Worldbuilding**. |
| **Storyline** | Your session notes: text files organised in folders, shown as a story-arc timeline. |
| **Battle map** | A grid map where encounters are fought with tokens. |
| **Token** | A character, NPC, monster or beast placed on a battle map. |
| **Online session** | A live, encrypted connection that shares the campaign state with players' computers. |

---

## 7. Accounts and sign-in

Everyone needs an account. One account works for both GM and player roles.

- **Log in** with your username or email and your password.
- **Create an account** needs a username, an email address and a password of at least 6
  characters.
- **Verify your email.** The app sends a verification link, or a code you enter on the
  **Verify your email** screen. Didn't get it? Check spam, then click **Resend code**.
  If the app can't send email at all, it shows the code on screen, so you can always finish.
- **Forgot password?** Enter your email. You receive a link, or a code to enter on the
  **Choose a new password** screen.
- **Log Out** is on the role-choice screen.

Accounts are stored online, so you can sign in from any computer with the app installed.

---

## 7a. Language

The app starts in **English**. To switch, pick a language from the **🌐 language menu** on the
login screen or in the GM menu header. Every screen and window changes language right away, and
the choice is remembered next time.

- Available languages: English, Arabic, Chinese (Simplified), French, Georgian, German, Hindi,
  Italian, Japanese, Korean, Polish, Portuguese (Brazil), Russian, Spanish, Turkish and Ukrainian.
- Translations are built into the app. They work offline and nothing is sent anywhere.
  They are machine-made, so some wording may be imperfect; English is always the reference.
- Your own content (campaign names, notes, storyline text, character names) is never
  translated. Rules content such as spell, item and creature names stays in English.
- Printed character sheets stay in English.
- Choose **English** to switch back at any time.

---

## 8. Campaigns and members

### Your campaign list

After choosing **Game Master** you see every campaign you are a GM of, with your role in it.

- **Open Campaign**, or double-click a campaign, to open it.
- **Create New Campaign** makes a new one. Tick **Start blank (no template data)** for empty
  catalogues; otherwise it starts with the [starter world](#the-starter-world).
- **Delete Selected** deletes a campaign. Only the author GM can do this, and the app asks for
  confirmation first.
- **On this device, not registered to anyone** lists campaign folders on your computer that
  have no owner yet, for example ones made before accounts existed. Click **Claim** to become
  their author GM.
- **Yours, but not on this device** lists campaigns you belong to whose files are on another
  computer.

When you are offline, the list you saw last time is shown with a note at the top.

### Syncing campaigns between computers with GitHub

Almerion can keep a campaign in sync between your computers using a free, private GitHub
repository.

1. **Link your account (once per computer):** on the campaign list click **Link GitHub
   Account…**, then **Copy Code and Open GitHub**, paste the code on the GitHub page and approve.
   If you are asked for a token instead, follow the on-screen link to create a *fine-grained
   personal access token* with access to "All repositories", **Contents: Read and write** and
   **Administration: Read and write**, and paste it.
2. **First computer:** select your campaign, click **Sync with GitHub…** and choose **Create a
   new private repository** (or pick one you already have).
3. **Other computer:** link the same account, click **Get Campaign from GitHub…** and pick the
   repository. The campaign appears in your list.

After that, syncing is automatic. The latest changes are downloaded when you open the campaign,
and your changes are uploaded while you play, when you leave the campaign and when you close
Almerion. The badge in the GM screen shows **Synced**, **Syncing…**, **Offline – will retry**
(nothing is lost; it uploads when you are back online) or **Conflict**.

**Conflicts:** if the same file was changed on both computers before syncing, your version is
kept and the other computer's version is saved next to it as `name.remote-<date-time>`. Compare
them, keep what you want, and delete the copy.

To stop syncing a campaign, use **Sync with GitHub… → Stop Syncing**. **Unlink** removes your
GitHub sign-in from this computer. Your sign-in is stored only on this computer (encrypted for
your Windows user) and is never put into a campaign folder.

### Inviting people: the Members window

Open it with **👥 Members** in the GM menu.

- **Join code:** **Copy** it to share it. **New Code** replaces it, and the old code stops
  working.
- **Waiting to join:** each applicant has **Accept as Player**, **Accept as GM** and
  **Reject**.
- **Members:** everyone is labelled *Author GM*, *GM* or *Player*. You can **Make GM**,
  **Make Player** or **Remove** them. Only the author GM can promote or remove GMs.
- **Refresh** reloads the lists.

People who aren't members can't see a campaign or join its sessions.

### The starter world

A campaign created without **Start blank** comes with original, ready-to-play content based on the
standard fantasy rules:

| Catalogue | Entries | Contents |
|---|---|---|
| Races | 21 | The core ancestries and their common subraces |
| Classes | 12 | All core classes with features and subclasses, plus the homebrew Artificer |
| Items | 94+ | Weapons (including magic ones), armour and shields, potions, adventuring gear |
| Spells | 67+ | Cantrips to 9th level, with components, range and cost |
| Monsters | 59 | From goblins (CR 0) to an ancient red dragon (CR 24) |
| Beasts | 26 | Animals and mounts |
| NPCs | 22 | Townsfolk, patrons and villains |
| Places | 18 | Settlements, dungeons and landmarks, most linked to a map |
| Maps | 12 | Tavern, village square, smithy, temple, keep, forest clearing, crypt, cave, bandit camp, wizard's tower, open ground... |
| Effects | 34 | Conditions and ongoing effects |
| Languages and books | 18 / 26 | Languages with primer books, plus in-world lore |
| Other | — | Damage types, alchemy ingredients, dice |
| Players | 6 | A pregenerated, equipped level 3 party |
| Storyline | 3 sessions | A starter chapter that shows read-aloud text and map links |

---

## 9. The GM menu

The GM menu is the home screen of an open campaign. Its header reads
*Game Master of campaign: &lt;name&gt;*.

| Section | What's in it |
|---|---|
| **▶ Run the Session** | **📜 Open Storyline** (session notes), **👥 Members**, **🖨 Print Character Sheets**, **🌐 Online Session** (shows ● while live) |
| **🎵 Music** | Play, pause, skip and stop controls, plus **Music Panel** |
| **☻ Players** | A card per character with name, level, player and HP. Scroll with ◀ / ▶. Click a card to open the character. **Create Character** starts the wizard. |
| **🏰 Worldbuilding** | Every catalogue, grouped: *Creatures* (NPCs, Monsters, Beasts), *Places* (Places, Maps), *Items & Lore* (Items, Books, Alchemy Ingredients), *Magic & Rules* (Spells, Effects, Damage Types, Dice), *Peoples* (Classes, Races, Languages) |

---

## 10. Characters

### Creating a character

Click **Create Character** in the GM menu. The wizard has eight steps:

1. **Race**
2. **Class**
3. **Subclass**: each subclass has an overview of its theme and play style, and every feature
   with the level you get it
4. **Level & Ability Scores**
5. **Spells**: the class's default spells are pre-selected, and you can change them
6. **Starting Items**: the class's default equipment is pre-selected, and you can change it
7. **Identity**: name, player, portrait, appearance and backstory
8. **Summary**

Every option shows a short explanation when you select it, so new players learn as they go.
Hover any spell or item to see its full description.

### Editing a character

Click a character card, or open the player list, then **View / Edit**.

- Long text fields (description, backstory) are large, and the **Save** button stays at the
  bottom of the window while you scroll.
- Lists such as items, spells and spell slots are compact, one line per entry.
- **Item, spell, tool and blueprint fields are searchable.** Click the field, type part of the
  name, then click a result or press **Enter** to take the first match. Adding a new row
  opens the search immediately.
- Hover a field's label for an explanation of what it does.

---

## 11. Worldbuilding

Each Worldbuilding button opens a catalogue list. Each entry shows a one-line summary, for example
the challenge rating and ability scores of a monster.

- **View / Edit** opens an entry. For a map, it opens the map editor.
- **New** creates an entry. For players, it starts the character wizard.
- **Delete** removes the selected entry.

The editor shows the whole entity, not just its name:

- **ability scores** as a grid, with each modifier shown;
- **choices** (challenge rating, spell school, habitat...) as drop-downs;
- **links to other entries** (class, race, item, spell, damage type...) as searchable
  pickers, so you never type an internal id;
- **lists** (monster abilities, carried items, known spells, languages) with add and remove;
- **images**: click **Upload Image**. The picker opens in your images folder, and the chosen file
  is copied into the campaign.

If a linked entry has been deleted, the link shows as `(missing)` and is kept, not silently
removed.

### Item kinds

Besides weapons, armour and potions, items can be a **Blueprint**, **Device**, **Tool**,
**Material** or **Fuel** (used by the [Artificer](#the-artificer)). Each kind shows its own fields.

### Hover cards

Hover a spell or item almost anywhere to see a card with its full description, sized to
fit. This works in character sheets, Manage Players, the wizard, pickers and battle maps, and on
spell or item names written in your session notes.

---

## 12. Storyline and session notes

Open it with **📜 Open Storyline**.

### The storyline screen

- A **file tree** of session files and folders: **New Folder**, **New File**,
  **Move to Folder**, **↑ / ↓** to reorder (dragging also works), and **Delete**.
- A **story-arc timeline**: each session is a point, and each folder is an arc spanning its
  sessions. The order always matches the tree.

### The session editor

Double-click a file to open it. The editor fills the window.

| Tool | What it does |
|---|---|
| **Insert Info** | Pick any catalogue entry (NPC, monster, item, spell...) from a searchable menu and insert a formatted summary |
| **Insert Block** | Templates for a scene, encounter, NPC beat, skill check, loot or session recap |
| **Read-Aloud** / **GM Note** | Mark text as player-facing prose or private notes |
| **Player View** | A large, read-only window showing only the read-aloud passages |
| **Insert Map** / **Open Map Link** | Insert a link to a battle map. Double-click the link to open the battle |
| **Sound Cue** / **Music Panel** | Insert music or sound cues, or open the music panel (see [Music](#14-music-and-sound)) |
| **Heading, Bullets, Checklist, Divider** | Basic formatting. Headings are coloured |
| **Find** | Search the text, with match case and *n of m* |
| **Notes** | A small scratchpad shared by every session file in the campaign, saved as you type |
| **Manage Players** | A side panel for running the session (see below) |
| **Timer** | Counts up, or down from a set number of minutes |
| **Save** / **Save & Close** | Saves the file (also **Ctrl+S**) |

### Manage Players

This panel runs the session from inside your notes:

- A card for every player character, plus any monster, NPC or beast you **+ Add** to this
  session. Added creatures are separate copies with their own HP.
- Quick **−** / **+** buttons damage or heal by the amount you choose.
- Click a card for details: vitals, items, effects, class features, rests and level-ups.
  **+ Add Item** and **+ Add Effect** open a searchable list. **✚ New item...** creates a new
  item and gives it at once.
- **★ XP** (or **Award XP** in the toolbar) gives experience to the whole party or the ticked
  players.

---

## 13. Maps and battles

### Map editor

Open **Maps** in Worldbuilding and click **View / Edit**.

- **Map Objects (layers & drawings):** add image layers (**+ Add Layer**), reorder them
  (**↑ Forward** / **↓ Back**), and **Group Selected** / **Ungroup** them.
- **Draw & Paint:** shapes and painted areas.
- **Tokens:** place creatures, or **Remove Selected Token**.
- Mark cells as impassable (walls, furniture, deep water). They show as black.
- **Save Map** saves your changes. **View Map** shows the read-only view, where **Run Battle**
  starts a fight.

### Battle map

Open it from a map link in your notes, or with **Run Battle**.

- **Turn order:** **Roll Initiative** rolls d20 + DEX for everyone (or enter rolls yourself).
  **Next Turn** moves through the order, skips the dead and counts rounds.
- **Tokens:** add, move and remove them. Click a token to see its sheet: portrait, stats, HP and
  mana, coins, items, spells and abilities. Click empty space or press **Esc** to deselect.
- **Damage and healing:** edit HP in place, and choose a damage type for resistances. At 0 HP a
  creature falls unconscious. **Failed Save** counts down 3 → 2 → 1, then it dies.
  **Made Save** or healing brings it back.
- **Ranges:** hover a spell or ability to see its range around the token, with every creature in
  reach highlighted.
- **Attacks:** pick a weapon or unarmed strike and click the target. The app shows d20 + bonus
  against the target's AC and adds the bonus damage.
- **Rests:** **Short Rest** and **Long Rest** restore the party's features and resources.
- **Terrain:** paint difficult ground, water or climbable areas. **Walls** toggles showing them.
- **📽 Projector:** opens a clean copy of the map, without GM panels or health bars, for a
  second screen. **Esc** closes it.
- **⚔ Combat Music** and **Map Music:** see [Music](#14-music-and-sound).

---

## 14. Music and sound

Music plays inside Almerion on the **GM's computer only**; no browser or account is needed.
Open it with **Music Panel** in the GM menu, or with **🎵** on the battle map or in the session
editor.

- **★ Starter pack:** adds free, openly licensed playlists (Tavern, City market, Castle, Village,
  Travel, Forest, Dungeon, Mystery, Horror, Combat, Boss fight, Sad, Camp, At sea), ambience and
  sound effects. They download once, with a progress bar, and then play offline. It only adds
  what's missing. **Credits** lists the author and license of every track.
- **+ Add audio files…** / **+ Add folder…** copy MP3, WAV, AIFF or M4A files into the campaign's
  `music` folder, so backups and moving to another computer keep them.
- **+ Add link…** takes a *direct* link to an audio file (ending in .mp3, .wav, .m4a…). It is
  downloaded once into `music/cache`. Links to YouTube, Spotify, SoundCloud and other web pages
  can't be used.
- **Playlists** have **Shuffle** and **Loop**; switching playlists crossfades. Double-click a
  track to play it. A track can start at a set time (for example 1:30).
- **Combat playlist:** plays automatically when initiative is rolled on a battle map, and
  switches back when the map closes.
- **Map music:** give a map its own playlist that starts whenever the map opens.
- **Ambience:** looping sounds such as rain, wind, a fireplace or a cave play under the music.
  Click to start and click again to stop.
- **Sound effects:** short clips you trigger with a button. The music dips while they play.
- **Session cues:** write `[music: Playlist name]`, `[music: stop]`, `[sfx: Sound name]` or
  `[sfx: stop]` in your notes, or use **Sound Cue**. Double-click a cue to play it.
- **Quick controls** (⏮ ⏸ ⏭ ⏹) are in the GM menu.

> **Only use music you own or that is licensed for your use.** Libraries from older versions may
> contain YouTube links. They are shown with ⚠ and skipped; click **Remove unsupported**, or press
> **★ Starter pack** to replace same-named sounds.

---

## 15. Online sessions

An online session shares the campaign with players' computers while you play. The data is
AES-256 encrypted.

**GM:**

1. Click **🌐 Online Session** in the GM menu.
2. Enter a **session passphrase** and click **Start Online Session**.
3. Members can now click **Join Live Session**. You can also give the **Player Token** to
   someone who needs to join by token.
4. The campaign is pushed automatically each time you return to the GM menu. **Push State Now**
   sends it immediately.
5. Click **Stop Session** when you're done.

**Player:** in **My Campaigns**, click **Join Live Session**. Or open
*Join with a session token instead*, paste the token and click **Join Session**.
**Go to My Character** opens your sheet, and **Leave Session** disconnects.

---

## 16. Printing character sheets

1. In the GM menu, click **🖨 Print Character Sheets**.
2. Tick the characters (or **Select all**) and click **Print**.
3. One PDF per character is saved to your **Downloads** folder. **Open folder** shows them.

Each sheet follows the standard fifth-edition character sheet layout:

- **Page 1:** abilities, saves, skills, combat stats, attacks and resources.
- **Following pages:** the character's portrait (or an empty frame to draw in) next to their
  appearance, then features, spells, equipment, backstory and lined notes.

Values that change during play, such as current and temporary HP, used spell slots, feature uses,
death saves and coins, are left as empty boxes to fill in with a pencil.

---

## 17. Rules reference: class features and the Artificer

### Class and race features

Characters automatically get their class features up to their level, their subclass features and
their race's traits. These work in battle, not just on paper, and you'll find them under
**Class & Race Features** on the battle map and in Manage Players.

- **Armor Class** is calculated from armour, shields, worn items and effects, including Unarmored
  Defense. Hover it for the breakdown.
- **Usable features** have buttons. Rage, Second Wind and similar features apply at once.
  Targeted ones, such as Lay on Hands, Bardic Inspiration and Breath Weapon, let you click a
  token.
- **Resistances** (Rage, tiefling fire, dwarf poison...) halve damage of that type
  automatically. Relentless Endurance and similar traits leave the character at 1 HP instead of
  0, once per long rest.
- **Short rests** restore features such as Second Wind, Action Surge, Ki and Channel Divinity.
  **Long rests** restore everything.
- New characters get their race's ability bonuses added automatically.

### Spell components

Every spell lists its components: **V** (verbal), **S** (somatic) and **M** (material). Material
components name the material, its cost, and whether the spell uses it up.

### The Artificer

The Artificer is a homebrew half caster. It uses **energy charges** instead of spell slots.
It has the same number per level as a half caster has slots, and each level is a different
charge type:

| Level | Charge | Fuel units per charge |
|---|---|---|
| 1 | Steam-pressure | 2 |
| 2 | Thermal | 4 |
| 3 | Shock | 7 |
| 4 | Force | 10 |
| 5 | Arcane Resonance | 14 |

- **Workshop furnace:** a Tool item with a *tier*, the highest charge level it can make.
- **Fuel:** each piece gives units: firewood 2, charcoal 3, coal 4, lamp oil 5. One coal makes
  two Steam-pressure charges or one Thermal charge.
- **Rests:** a **long rest** burns carried fuel to refill all missing charges up to the furnace
  tier. A **short rest** refills each charge type up to half of its maximum. The weakest fuel burns
  first.
- **Mechanical devices** accept a charge of their level or stronger. Each level above adds one
  damage die. **Tuned devices** only take their own type; for example, the Thermal Smoke Grenade
  needs a Thermal charge.
- **Blueprints** list a product, materials (used up), tools (kept), any charges needed and a
  minimum level. Copy a blueprint into the **blueprint book** to build it any time you have the
  parts.
- **Crafting check:** d20 + Intelligence modifier + proficiency against the blueprint's DC.
  - On a success, the item is built.
  - Failing by less than 5 costs nothing.
  - Failing by 5 or more ruins the materials.
  - A natural 20 always succeeds and a natural 1 always fails.
- **18 blueprints** are included:
  - *Utility and control:* Smoke Canister, Tripwire Alarm, Steam Grapnel Launcher, Sticky Foam,
    Messenger Beetle, Thermal Smoke Grenade.
  - *Light combat:* Scalding Bolts, Flash Bomb, Shock Darts, Steam-Jet Gauntlet.
  - *Medium combat:* Thermal Grenade, Pressure Ram, Concussion Bolts.
  - *Capsules:* 5 blueprints that refill a charge from parts.
- **Workshop** (in Manage Players) shows charges and fuel. It has buttons to burn fuel, make a
  charge, copy a blueprint and build one. When something is missing, the button explains what.

---

## 18. Keyboard shortcuts

| Where | Keys | Action |
|---|---|---|
| Session editor | **Ctrl+S** | Save |
| Session editor | **Ctrl+F** | Find; **Enter** / **Shift+Enter** go to the next / previous match, **Esc** closes |
| Search pickers | Type, then **Enter** | Pick the first match; **↓** moves into the list |
| Battle map | **Esc** | Deselect the token or cancel the action |
| Map editor | **Delete** / **Backspace** | Delete the selected object |
| Map editor | **Esc** | Cancel |
| Projector window | **Esc** | Close |
| Campaign list | Double-click | Open the campaign |

---

## 19. Your data and backups

Everything you create is stored in your Windows user profile, not in the install folder:

```
%APPDATA%\Almerion\
├── data\
│   ├── custom-campaigns\<campaign>\   one folder per campaign: catalogues, players,
│   │                                  maps, storyline, images, music.json, music\
│   ├── auth\                          sign-in and connection settings
│   ├── player-profiles\               players' own portraits and appearance text
│   └── joined-sessions\               the last state of sessions you joined as a player
├── images\                            your picture library (image pickers open here)
└── app\                               downloaded updates
```

To open it, press **Win+R**, type `%APPDATA%\Almerion` and press Enter.

> **Upgrading from "DnD Campaign Manager"?** The first time Almerion starts, it moves your old
> `%APPDATA%\DnD Campaign Manager` folder here automatically, so all your campaigns carry over.
> Old `[DM NOTE]` markers in session notes keep working.

- **Back up** a campaign by copying its folder from `data\custom-campaigns`. To restore it, copy the
  folder back while the app is closed.
- **Move to a new computer** by copying the whole `Almerion` folder.
- **Printed sheets** go to your **Downloads** folder.
- **Music** lives in each campaign: `music.json` holds playlists, sounds and credits, `music\`
  holds imported audio, and `music\cache\` holds downloaded audio, which can be deleted safely
  (it downloads again when needed).

Membership, join codes and accounts are stored online. Campaign content stays on the GM's
computer, so back it up.

---

## 20. Troubleshooting

| Problem | What to do |
|---|---|
| **No verification or reset email** | Check spam and click **Resend code**. If email can't be sent, the app shows the code on screen. On the login screen, **Test email settings** reports the exact reason. Office, university and VPN networks often block outgoing email. |
| **"You aren't part of ..."** | You aren't a member yet. Apply with the join code and wait for a GM to accept you. |
| **A campaign I created is missing from my list** | Look under *On this device, not registered to anyone* and click **Claim**. If it's under *Yours, but not on this device*, its files are on another computer. |
| **"Offline - showing the campaign list saved from your last connection."** | You aren't connected. Local campaigns still open; joining and members need the internet. |
| **Music doesn't play** | Check that the Windows volume isn't muted. A track marked **⬇** is still downloading or failed: check the internet connection and press **★ Starter pack** again. |
| **"That link is a web page, not an audio file."** | Use a direct link to an .mp3/.wav/.m4a file, or add a file from your computer. |
| **A track shows ⚠ "YouTube links are no longer supported"** | Replace it with an audio file, or click **Remove unsupported**. |
| **An OGG or FLAC file won't add** | Convert it to MP3 or M4A first. |
| **The update failed** | The app tries again next start. You can also install the latest `.exe` from the [releases page](https://github.com/irakli-2003/Almerion/releases/latest). |
| **SmartScreen blocks the installer** | Click **More info → Run anyway**. |
| **The projector shows on the wrong screen** | Drag the *Battle Map - Players* window to the second screen and maximise it. |
| **A field shows `(missing)`** | The entry it pointed to was deleted. Pick a new one or leave it. |

---

## 21. FAQ

**Do players need to install the app?**
Only to join online sessions or view their own character. A GM can run an in-person game alone,
using the projector and printed sheets.

**Can two GMs work on one campaign?**
Yes. Accept someone as **GM** in the Members window. They need a copy of the campaign folder to
edit it on their own computer.

**Can I use my own rules and content?**
Yes. Create a campaign with **Start blank** and build everything in Worldbuilding, or edit and
extend the starter world.

**Does the app play music for players too?**
No. Music plays on the GM's computer only, so use speakers at the table.

**Can I use YouTube or Spotify?**
No. Their terms don't allow playing their audio inside other apps. Use files you own or openly
licensed audio such as the starter pack.

**Can I use the starter pack in streams or published games?**
Yes, with credit: see **Credits** in the music panel (CC BY 3.0 requires attribution; CC0 does not).

**Is it an official product of any tabletop game publisher?**
No. See [Legal notice](#23-legal-notice).

**Is the interface available in other languages?**
Yes. See [Language](#7a-language). Guides in other languages are in the `Documentation` folder
(see below).

---

## 22. More documentation

- [`Documentation/guides`](Documentation/guides): character creation guide (English and Georgian).
- [`Documentation/rules`](Documentation/rules): the rules the app follows, such as core rules,
  combat, magic, rests, conditions, homebrew changes and a GM guide.
- [`Documentation/catalog`](Documentation/catalog): reference lists of classes, races, items,
  spells and creatures.
- [Third-party notices](THIRD-PARTY-NOTICES.md): licenses of bundled libraries, fonts and music.
- [Developer Guide](DEVELOPER.md): building from source, the command-line version, configuration
  and releasing.

---

## 23. Legal notice

Almerion is independent software. It is **not affiliated with, endorsed, sponsored or approved by
Wizards of the Coast** or any other tabletop game publisher. Names of other products are used only
to describe compatibility.

The bundled rules content is original text written to describe game mechanics from the
*System Reference Document 5.1* and *5.2* by Wizards of the Coast LLC, available at
https://dnd.wizards.com/resources/systems-reference-document and licensed under the
Creative Commons Attribution 4.0 International License
(https://creativecommons.org/licenses/by/4.0/legalcode). Monsters, subclasses and other content
outside the SRD are original or renamed homebrew. The Artificer is a homebrew variant.

Bundled libraries, fonts and starter-pack music and sounds belong to their authors and are used
under their licenses; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
