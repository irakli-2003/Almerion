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
15. [Hosting a session](#15-hosting-a-session)
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

**Playing at the same table?** If your GM hosts a *local network* session you don't need the
app at all: open the address your GM gives you (for example `http://192.168.1.20:8765`) in any
browser on a phone, tablet or computer on the same Wi-Fi. Pick your character and enter its
password; your sheet then updates live as the GM changes it.

Your GM controls stats, level, items and spells. Your portrait, name and appearance text are
kept on your own computer.

### Switching from GM to player

Use **Switch to Player Mode** in the GM menu, or **Player** on the role screen. Stop both
internet and local hosting first; Almerion never disconnects joined players automatically
as part of a role switch. Pending actions must finish first.

The switch closes secondary windows through their normal close requests, preserving
save/discard/cancel prompts. If a window refuses to close, you remain a GM. After a
successful switch, private-file keys lock, campaign/character/map selections clear, and
GM routes and stale GM previews are blocked. The player screens display a **Player Mode**
banner. Join your co-GM's hosted session normally; this uses the player-safe snapshot,
not the old GM campaign folder.

Use **Choose role → Game Master** to return, then open a campaign through the normal GM
membership checks. Private files must be unlocked again and their owners' grants still
apply. Player Mode does not change campaign membership, revoke already-read copies,
clear the operating-system clipboard, or restrict access to files outside the app.

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
| **Online session** | A live, encrypted connection that shares the campaign state with players' computers over the internet. |
| **Local session** | A small web page served from the GM's computer so players on the same network can follow their character sheet in a browser. |

---

## 7. Accounts and sign-in

Everyone needs an account. One account works for both GM and player roles.

- **Log in** with your email and password. Username login also works after this device has
  remembered the account's email.
- **Create an account** needs a username, an email address and a password of at least 6
  characters.
- **Verify your email.** Online accounts require Firebase's emailed verification link.
  Check spam. An old device-local account may need to enroll/verify its existing email
  first; no second campaign profile is needed. No on-screen code can bypass online verification.
- **Forgot password?** Enter your email and use the emailed password-reset link.
- **Log Out** is on the role-choice screen.

Firebase handles online passwords; the shared directory stores only verified profile details
and memberships, never password hashes or reset codes. Offline sign-in uses previously cached
credentials and opens local campaigns with a read-only cloud list; online changes and hosting
require signing in online again. Fully local installations (`-Ddnd.cloud=off`) retain their
separate local account/code flow.

The authenticated-cloud update requires an administrator-assisted migration before deployment.
Older versions cannot access online accounts or sessions after cutover, but local files remain.

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
While a battle map is open, changes are uploaded every 10 minutes instead of every 90 seconds,
so the fight stays smooth. The normal pace resumes when the battle closes.

**Conflicts:** independent catalogue entries and nested object fields merge automatically,
including edits to different characters or different fields of the same character. Entries
are matched by ID, not array position or JSON lines. Unknown fields are preserved. Competing
values show the character/entry, field path, common version, and both proposed values.
Choose **This computer** or **GitHub** for each competing value; unrelated edits from both
devices survive. Ordered lists (inventory, spells, etc.) are chosen as a unit rather than
combined by position. Deletion versus an edit, or different new entries with the same ID,
requires an explicit choice.

Other files, unsupported legacy catalogue shapes, and whole-file deletion conflicts retain
file-level review, including **Keep both** (GitHub's copy becomes `name.remote-<date-time>`).
Each side shows its last file author, computer, and time; these are not per-field timestamps.
The newer file is the default suggestion, not an automatic decision. A field choice is
invalidated if either reviewed file version changes before applying it. **Decide Later**
keeps this computer's files unchanged, uploads nothing, and asks again at the next sync.

**Campaign identity:** the campaign list shows playable and unclaimed local campaigns.
Online registrations are kept in the background rather than listed separately. Matching
names do not merge campaign IDs or delete registrations; access and identity errors are
shown when an attempted action needs attention. Use **Get Campaign from GitHub…** or
**Import Complete Campaign…** to bring a campaign onto this device.

**Put-aside versions:** versions saved next to a file as `name.remote-<date-time>` (by **Keep
both**, or automatically by Almerion 1.2.1 and older) are listed when you open the campaign, and
under **Sync with GitHub… → Put-Aside Versions**. For each one choose **Use the put-aside
version** (it replaces this computer's file), **Keep this computer's file** (the copy is deleted)
or **Decide later**.

To stop syncing a campaign, use **Sync with GitHub… → Stop Syncing**. **Unlink** removes your
GitHub sign-in from this computer. Your sign-in is stored only on this computer (encrypted for
your Windows user) and is never put into a campaign folder.

**Large music, images and other files:** files larger than **100 MiB** stay in your campaign
folder but are excluded before Git staging/upload. A small checksummed manifest syncs instead.
After syncing, Almerion lists files that were not uploaded, or are missing/different on this
device. Choose **Ignore and Continue** to leave them alone, or **Copy Files to Folder…** to
create one new transfer folder in a location you choose (Desktop by default). Keep that folder's
contents and directory layout intact when moving it to the other device, then choose
**Import Files from Folder…** there. Imports verify every file and never overwrite a different
local copy: move that copy somewhere safe first. You can reopen this tool under
**Sync with GitHub… → Files Not Synced through GitHub…**. No Google account is required.
The chosen transfer folder is remembered per campaign on this device. On later sync/open checks,
Almerion verifies its copies and quietly adds new large files or refreshes changed versions,
preserving replaced copies as `.previous-<id>`. Verified copies no longer trigger repeated
notifications, including after app restarts. To reuse an export made before this feature, select
that existing transfer folder with **Copy Files to Folder…** once. Missing files on a receiving
device still show the import reminder. If the saved folder is unavailable, the copy reminder
returns; locally changed transfer copies are never silently overwritten.

If an older app already committed oversized files and GitHub rejected the upload, use
**Sync with GitHub… → Repair oversized history…**. After confirmation, Almerion fetches the
published history, backs up the original local commits inside `.git`, and removes oversized
blobs only from unpublished commits before retrying. It does not delete your campaign files,
rewrite published commits or force-push. Keep the `.git` folder if you need that history backup.
Update every device before syncing so an older version does not recommit large files.

**Complete campaign transfer:** the campaign list has **Export Complete Campaign…** and
**Import Complete Campaign…**. Export produces one `.almerion.zip` containing all files stored
inside the campaign, including large audio, images, maps and campaign identity. Git history,
GitHub connections and app-level account credentials are not included. Downloaded music caches
are included; links that were never downloaded remain links.
If the campaign's transfer manifest lists missing/different files, import their verified copies
first so the complete export does not silently omit known assets.

Import identifies the campaign by its ID, even after a folder rename. If it already exists,
missing files are added and newer imported files replace older local files; local-only content
and newer/equally dated local versions are kept. This is a **file-level merge**, not a merge of
individual characters/items inside a JSON file. Replaced files are backed up under
`campaign-import-backups` beside `custom-campaigns`. Files are verified before the existing
campaign is changed. Keep device clocks correct because file modification times determine which
version is newer. A same-named campaign with a different ID is imported into a separate folder.
Existing campaign membership rules still apply; an unregistered import can be claimed below.

If Git cannot stage a file, Almerion shows the underlying file/permission error instead of
just "add command failed". An index lock is never deleted automatically: close other app or
Git processes before retrying. A lock left by a crash should only be removed after confirming
no process is using this campaign repository.

### Inviting people: the Members window

Open it with **👥 Members** in the GM menu.

- **Join code:** **Copy** it to share it. **New Code** replaces it, and the old code stops
  working.
- **Waiting to join:** each applicant has **Accept as Player**, **Accept as GM** and
  **Reject**.
- **Members:** everyone is labelled *Author GM*, *GM* or *Player*. You can **Make GM**,
  **Make Player** or **Remove** them. Only the author GM can promote or remove GMs.
- **Refresh** reloads the lists.

The directory shows full campaign records only to accepted members. Keep session tokens private:
they are bearer decryption capabilities, and anyone holding one can decrypt its player snapshot.

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
| **▶ Run the Session** | **📜 Open Storyline** (session notes), **👥 Members**, **🖨 Print Character Sheets**, **📡 Host Session** (shows ● while a local or internet session is running) |
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
- **Skills** (Stealth, Animal Handling, Sleight of Hand and the other 15) appear as a grid. Each
  is a whole number: positive, negative or 0. A character created before skills existed starts
  from its ability modifiers, so you only need to adjust the skills it is proficient in. Players
  see their skills on their character profile, and the printed sheet uses the same values.
- **Delete Character**, next to **Save**, removes the character permanently after you confirm.

### Class upgrade trees

Open **Worldbuilding > Class Upgrade Trees**, choose a class, and edit its visual tree.
Each class starts with a four-tier tree: an always-unlocked class foundation, two named
training paths, four middle nodes and eight leaf nodes. Every non-leaf starter node has at
least two children. Ordinary paths let a character specialize; selected stronger leaves
also require training from the other path. Named vertical sections show branch membership,
so branch names are not repeated on node cards. Add or rename branches and click a node to
edit its **Parent Id**, unlimited **Children**, and additional **Prerequisites**.
The parent is always required; every additional prerequisite must also be unlocked.
Changing either end updates the reciprocal child/parent links. Removing a child reconnects
it to the foundation; edit that child's parent to move it elsewhere.
Prerequisites can cross branches. Branches may mix unless the GM links specific nodes as
**Exclusive With**; a single link prevents taking both choices in either order. Cycles,
missing links and mutually exclusive prerequisites cannot be saved.

Required character level is the node's tier in its parent hierarchy: foundation 1,
then 2, 3, 4 and so on. Additional prerequisites do not move a node into a later tier,
but can require spending more choices before it can be unlocked. Minimum levels update
automatically when parents change; an ordinary starter path can reach a leaf at level 4.
Nodes have a name, feat description and an optional uploaded picture shown
in a standard 48-pixel frame. Configure permanent HP, mana or ability-score increases,
AC, weapon/unarmed attack bonuses, basic attack damage, Rage damage and walking speed.
To grant an active ability, first create it in **Spells**, then select it under the node's
**Spell Ids** reward. It uses that spell's existing casting costs, effects and cooldowns.
Every node must also grant a named feat under **Features** or an ability under **Spell Ids**.
Any class can receive spell rewards. Only caster starter trees include them by default:
cantrips near the foundation, rank-1 spells next, then rank-2 leaf rewards for full/pact
casters. Half/third casters and energy-based artificers keep rank-1 leaf spells.
Under **Spell Alternatives**, select a primary spell from **Spell Ids** and predefine its
same-spell-level replacement list. A character who already knows the primary chooses an
unlearned spell from that list, never an arbitrary spell. If no unlearned options remain,
a warning allows continuing with the feat and other bonuses without learning a new spell.
Cancelling leaves the unlock unspent. Saved rewards record the spell actually learned.
Spell grants do not add free spell slots, refill resources, or change casting rules; the GM
can configure a non-caster's casting resources separately.
Feats can specify **Uses Per Rest** and **Short Rest Recharge** to get combat use counters.
Recognized class feats retain their built-in mechanics; other feats give explicit GM-resolved
effects, action costs and limits. Numeric bonuses alone are not sufficient to save a node.
Yellow routed lines show both parent and additional prerequisite links, with generous
space between tiers. Use the zoom slider, **Ctrl+ / Ctrl-** (including numpad keys),
and drag the graph to explore the branches. Zoom is retained when the view refreshes.
Whole-tree saves require at least four tiers, eight leaves, two children per non-leaf
and a multi-prerequisite choice. Intermediate edits can be incomplete while you build.

Use **Class Upgrade Tree** on a character's **Progression & Rest** panel to spend choices.
Each level-up earns one; unspent choices can be saved. Existing level-5 characters start
with four choices **already spent**, not four free rewards: matching existing class features
and known spell nodes are marked as historical unlocks, with remaining old levels recorded
as existing training. No old ability or bonus is applied again. Existing class features
mark matching lower nodes unlocked; remaining historical records and removed definitions
are available by clicking the foundation, not drawn as a separate branch. The foundation
costs no choice and describes starting class features, hit die, saves and starting abilities.
Existing automatic class/subclass features continue unchanged;
tree branches are additional specialisations, not replacements for the legacy subclass.

Renaming or deleting a tree node does not refund choices or remove rewards already earned:
each unlock saves its original reward and description on the character. Close battle windows
before editing trees or applying unlocks. Players can inspect the tree from their profile;
the GM applies mechanical choices, just as with levels and spells. Numeric rewards appear
in combat, shared sheets and PDFs through the same rules as the character sheet.
Untouched older starter trees expand automatically. GM-customized and explicitly empty
trees are preserved. Previously earned numeric rewards keep their saved values.

---

## 11. Worldbuilding

**Stats & Skills** defines the campaign's ability scores and skill fields. Add, rename or remove
entries, choose their abbreviations, and assign each skill a parent ability. IDs are permanent
value keys: changing a label keeps values; changing an ID creates a different field. New abilities
start at 10 and new stored skill bonuses at 0. Existing campaigns retain the standard six abilities
and eighteen skills until you change them. Scores remain 1-30, with modifiers rounded down from
(score - 10) / 2; skill bonuses are editable independently.

Before removing a referenced ability, redirect its **Ability bindings** to a remaining ability ID.
The six standard roles drive their existing rules (for example Dexterity controls initiative,
dodging and ranged attacks, while Constitution controls HP calculations). Redirect **Skill bindings
→ perception** before removing the skill used for passive perception. Skills must also have valid
parent IDs. Removed custom abilities referenced by spells, classes, races or sheets need an alias
in Ability bindings. Saving refuses unresolved references instead of guessing. Retired numeric
values stay in campaign files; edit each creature's new fields as needed. Close battle windows
before changing these rules.

Each Worldbuilding button opens a catalogue list. Each entry shows a one-line summary, for example
the challenge rating and ability scores of a monster.

- **View / Edit** opens an entry. For a map, it opens the map editor.
- **New** creates an entry. For players, it starts the character wizard.
- **Delete** removes the selected entry.
- **Search** above the list finds entries by name, id or details (type several words to narrow
  it down). The drop-down next to it filters by kind, for example a spell's school or level, an
  item's type or a monster's type and challenge rating.

The editor shows the whole entity, not just its name:

- **ability scores** as a grid, with each modifier shown;
- **choices** (challenge rating, spell school, habitat...) as drop-downs;
- **links to other entries** (class, race, item, spell, damage type...) as searchable
  pickers, so you never type an internal id;
- **lists** (monster abilities, carried items, known spells, languages) with add and remove;
- **spells**: effects are picked from the campaign's effects, each with a power of a constant plus
  dice (rolled at the table when cast); damage is dice plus a flat constant; components are
  separate Verbal, Somatic and Material ticks with the material's description, cost and whether
  it is used up; required consumables and tools are picked from the campaign's items;
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

### Private session files and access requests

**New File** creates an encrypted session owned by the GM creating it. Enable **Unlock
private files** with your verified account password first, and save the recovery key outside
the campaign/GitHub. The same account password unlocks your keys on a second device; after
a password reset, use the recovery key. Losing both your keys and recovery key can make
private files unrecoverable. This requires administrator-deployed private Firebase rules
(see [DEVELOPER.md](DEVELOPER.md)); shared session tokens cannot unlock private files.
If your account predates Firebase Authentication, enter your existing password and choose
**Set up / verify email** in the unlock dialog. Open the emailed verification link, then
unlock again. This registers/verifies the same email with Firebase, not another Almerion
profile. If Firebase already has that email with another password, use its password or
the normal password-reset flow; do not create a duplicate profile.

Existing files remain shared. Select one and use **Session-file access** to explicitly
encrypt it and become its owner. Only that owner can grant other campaign GMs read/edit
access, change access, move, or delete the file. The campaign author has no automatic
override. Other GMs must enable their own private-file account before being selected;
verify their displayed email with them before granting access.

A denied GM can request access through the same panel, or by opening the private file.
The owner receives a request in **Notifications** in Storyline and the GM panel. Use
**Refresh**, review the verified recipient email, and approve or deny. Approval updates
the local encrypted file: the owner must sync it, then the requester must sync to receive
the grant. Requests and responses persist online while the app is closed.

Text and the session encounter roster are encrypted together. Sync/import/export carries
ciphertext, not decrypted session text. File names, folder placement, owner IDs, and grant
IDs are not concealed. Encryption cannot erase previously synced plaintext Git history,
old exports, or copies already read. Revoking access rotates the file key for future
versions; it cannot revoke old copies. Explicit **Player View**, clipboard sharing, global
catalogue changes, and the campaign-wide **Notes** scratchpad are still shared actions.
Chat and mobile/token-player notifications are the next implementation stage.

### The session editor

Double-click a file to open it. The editor fills the window.

With your private-file keys unlocked, **Recovery & Autosave** saves an encrypted draft
after a two-second typing pause, or every ten seconds during continuous editing. Drafts
are account-only copies under the device's `data/auth/storyline-drafts`, outside campaign
folders, GitHub sync, campaign archives, and hosted snapshots. Other GMs granted the
original file cannot decrypt your drafts. No draft is uploaded or copied to another device.

After a crash, reopen the same session file with the same account and unlock its keys.
Review the recovery preview alongside the current text before choosing **Recover into
editor**. A changed-source warning prompts review; recovery never writes the session file
automatically. Normal Save still rejects an externally changed file or permissions.
Recovery also requires access to the current source file; a revoked grant or replaced
private-file identity cannot be bypassed with a draft. Saving removes this editor's draft
and the selected recovered copy; closing without saving removes only this editor's draft.
Other recovery copies remain until explicitly deleted. Drafts are keyed to the original
absolute file path, so moving a campaign/file does not relocate its recovery copies.

If account keys are locked or unavailable, ordinary editing still works and the editor
clearly shows **Encrypted autosave is off**. Use **Recovery & Autosave** to enable/unlock
keys, or save manually. Initial key setup/unlock currently requires verified online access;
already-unlocked keys can save drafts offline. Keep the independent account recovery key:
encrypted drafts cannot recover lost encryption keys, and an abrupt crash can still lose
the edits made since the last completed draft.

### Local restore points

Open **Restore points** from the GM menu or campaign selection. Almerion keeps the latest
20 points per campaign on this device under `data/auth/restore-points`, outside campaign
folders and GitHub sync. Renaming a campaign keeps its history. Use **Create restore point**
for a manual snapshot; automatic snapshots precede campaign imports, incoming GitHub
merges, catalogue/storyline deletion, whole-campaign deletion, and app update downloads.
If a required snapshot fails, the operation stops before changing the campaign.

Snapshots contain the files available locally, not missing manual-transfer assets. They
are not a substitute for complete exports or an off-device backup. Compare saved/current
text, select files, then **Restore selected files**. Other current files remain unchanged.
Storyline files also restore their encounter rosters. Existing campaigns get a safety
point before restoration, subject to the same 20-point retention.

Only current campaign GMs may use its history. After online registration is deleted,
only points explicitly captured with your account identity (including campaign deletion)
are accessible to that account. Restoring a deleted campaign recreates its identity, not
its online membership or Git history. Linked campaigns share restored changes through
their normal GitHub sync.

Private-file access is checked again for previews and restoration. Historical grants
never replace current grants, and restoring old plaintext never removes current
encryption. A missing/replaced plaintext target of a private snapshot can only be
recovered by its original owner, as a fresh owner-only encrypted file.

### Local action history

Open **Action history** from the GM menu after closing editors and maps through their
normal save prompts. The latest **500 saved mechanical actions per campaign** remain
on the original device under `data/auth/action-history`, outside campaign exports and
GitHub sync. This is a saved-change journal: one save can group multiple changes,
particularly NPC/monster/beast battle changes. Player-character battle changes use the
character sheet as their authority.

Compare **Before action** and **After action**, then **Undo selected action**. Damage,
healing, resources, character inventory, effects and mechanical GM corrections are recorded.
Every recorded field must still match its after-value; otherwise nothing is undone.
Unrelated newer fields survive, and undo itself creates a correction record. Current
campaign GM membership is required. Entity creation/deletion and whole-file recovery
belong to restore points, not action undo. Rabbit's in-world rewind is separate.

Storyline text, explicit notes, passwords and chat are excluded. Private encounter
deltas are encrypted; preview and undo require current access and matching private-file
identity. Undo preserves current storyline text and grants, never downgrades encryption,
and does not revoke previously readable public copies or history. Mechanical effect
definitions and inventory structures remain part of the recorded mechanical values.

Interrupted saves recover automatically when the file still matches the before/after
revision. If an external edit makes the result uncertain, **Resolve interrupted actions**
lets you explicitly retain current campaign files and discard incomplete journal
entries; those uncertain actions can no longer be undone. A journal failure before
the data write prevents the mechanical change; a finalization failure explicitly
reports that the data was already saved.

| Tool | What it does |
|---|---|
| **Insert Info** | Pick any catalogue entry (NPC, monster, item, spell...) from a searchable menu and insert a formatted summary |
| **Insert Block** | Templates for a scene, encounter, NPC beat, skill check, loot or session recap |
| **Read-Aloud** / **GM Note** | Mark text as player-facing prose or private notes |
| **Player View** | A large, read-only window showing only the read-aloud passages |
| **Insert Map** | Insert a link to a battle map. Double-click the link to open the battle |
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

### Templates and named locations

**Maps → New** opens the template chooser. Choose a blank grid or a built-in layout such as
**Temple**, **Castle - Ground Floor**, **Cave**, **Tavern** or **Village Square**, then enter
the location's own name, for example **Queen's Castle**. New map names must be non-empty and
unique within the campaign (ignoring case and extra whitespace).

Every location belongs to a world map. Before **Create Map**, choose the world map under
**On world map:** and click the square where the location is; the new map is pinned there.
The Maps list shows which world map each location is on, and flags any that are not.

Templates are read-only starting layouts, not playable campaign locations. Each creation
produces an independent map with its own ID and state; editing one never changes the template
or another location made from it. To revisit the *same* location, reuse its existing map link.
Templates are available in existing campaigns too, without replacing their maps.

In the session editor, **Insert Map → Create Map from Template…** creates a named map and
inserts its link. With the cursor inside an existing map link, choosing a map replaces that
whole link, so a generic Temple link can become a separate Queen's Castle location.
**Rename Map…** in the map editor keeps the map's ID unchanged. Session link labels refresh
to the current name when notes are opened or saved; save the notes to preserve those labels
in the text file. World-map pins and map pickers also use the location name.

### Map editor

Open **Maps** in Worldbuilding and click **View / Edit**.

- **Map Objects (layers & drawings):** add image layers (**+ Add Layer**), reorder them
  (**↑ Forward** / **↓ Back**), and **Group Selected** / **Ungroup** them.
- **Draw & Paint:** shapes and painted areas.
- **Tokens:** place creatures, or **Remove Selected Token**.
- Mark cells as impassable (walls, furniture, deep water). They show as black.
- **Save Map** saves your changes. **View Map** shows the read-only view, where **Run Battle**
  starts a fight.

### World maps

Every campaign has at least one world map, and every location map is pinned on one. Open it
from the GM page with **Run the Session → 🧭 World Map**.

**Upgrading an existing campaign:** the first time a campaign without a world map is opened
(GM page, World Map panel or Maps list), a map that already had world pins or the party flag
becomes the world map, otherwise a blank **World Map** is created. All existing battle maps
that aren't pinned yet are then pinned in rows from the top-left corner of that world map,
so nothing is lost; drag each pin to where the place really is. The party starts hidden
unless an older map already had the party flag; use **🚩 Move Party** to place it. Update
the app on every computer that syncs the campaign, because older versions don't know which
maps are world maps.

- The picker at the top switches between world maps. **＋ New World Map...** creates more
  (continents, regions, the underworld...) with unique names; **Rename...**, **🖌 Edit Map & Images**
  and **Delete** manage them. The last world map can't be deleted.
  **🖌 Edit Map & Images** opens the normal map editor, so world maps can have background
  images (**+ Add Layer**), drawings and painted terrain just like battle maps.
  Its token list contains only maps, not players, NPCs, monsters or beasts. Click or drag a
  map from the list to add its pin, drag a placed pin to move it, and right-click or use
  **Remove Selected Token** to remove it. **Save Map** saves artwork and pin changes.
  Both editors have **−**, **+**, and **100%** zoom controls (25%–300%); zooming does not
  change map dimensions or drawing coordinates.
- **📍 Link Location** asks which map a square leads to (or creates a new location map), then
  you click the square. Double-click a pin to open that location in the battle map, or to switch
  to a linked world map. **✖ Remove Link** removes a pin; the destination map is kept.
- Drag a pin to another square to move it, or use **✥ Move Location**: click the pin, then
  the new square.
- **⚠ Not on a world map: N** appears when a location has no pin (for example after removing
  its link). Choose one and click its square, or **Place All Along the Top**.
- **🚩 Move Party** puts the party flag on a square. The party is always in exactly one place:
  moving it to another world map removes the flag from the previous one. **Hide Party** removes
  the flag; **Find Party** jumps to the map where the party is.
- **−** / **+** zoom. **Esc** cancels a placement. Changes are saved at once and take part in
  normal GitHub sync.

### Battle map

Open it from a map link in your notes, or with **Run Battle**.

- **Turn order:** **Roll Initiative** rolls d20 + DEX for everyone (or enter rolls yourself).
  **Next Turn** moves through the order, skips the dead and counts rounds.
- **Tokens:** add, move and remove them. Click a token to see its sheet: portrait, stats, HP and
  mana, saving throws, detailed skill bonuses, items, spells and abilities. Money belongs in
  inventory items, not separate denomination fields. Click empty space or press **Esc** to deselect.
  As GM, select a token and right-click any empty square to relocate it, ignoring movement
  range and terrain without spending its movement. Drag the divider beside the left panel
  to resize the turn order and character details.
- **Active effects:** every creature's details have **Add Effect...**, even when no effects
  are active. Choose a Worldbuilding effect and set its duration, damage and healing per round.
  **Remove** ends an active effect immediately; changes also update linked character sheets.
- **Rabbit's Pocket Watch Rewind:** use the rabbit's ability, not a toolbar button.
  Enter the rabbit's d20 roll; the Arcana DC is that roll + 5. Enter each player character's
  complete Arcana check total (including bonuses). Meeting or beating the DC keeps that
  character's current state and position; everyone else returns to the start of the rabbit's
  previous recorded turn. The ability's costs and cooldown remain spent. History lasts only while
  the battle window is open, so a prior rabbit turn must first have been recorded there.
- **Damage and healing:** type an amount in the visible field (default **1**), then click
  **Damage** or **Heal**. Choose a damage type for resistances. At 0 HP a creature falls
  unconscious. Successes and failures are independent: three failures kill; the third
  **Made Save** asks for a physical **1d4** roll and revives with that many HP, capped at max HP.
  **Revive** asks for the same roll; cancellation changes nothing. This is the campaign's custom
  revival rule, not standard D&D stabilization. A long rest still restores full health.
- **Casting rolls:** edit a spell or ability's **Checks** in Worldbuilding to require a caster
  attack roll, a target ability saving throw, or both. Set successful saves to **Half** or **None**
  damage, and optionally override the attack bonus or save DC. The GM can change these rules
  for an individual cast. Enter physical d20 rolls before damage: attacks use the target's AC;
  saves use the selected ability's bonus and the caster's DC. A save halves damage before
  resistance and avoids conditions. Misses and successful saves still spend costs and cooldown;
  cancelling the roll dialog does not. Existing actions remain automatic unless configured.
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

## 15. Hosting a session

Click **📡 Host Session** in the GM menu. There are two ways to share the game; you can use
either or both at once.

Players only ever receive what belongs to them: their character, the party, and the items,
spells and effects they actually have. Monsters, NPCs, maps, places, books, other GMs' notes
and passwords are never sent.

### Local network (players use a browser)

Best when everyone sits at the same table. Players need no account and no app.

1. Give each character a password. The Host Session screen lists every character with a
   **Set password** / **Change password** button. Characters without a password can't be
   opened.
2. Click **Start local session**. The screen shows one or more addresses such as
   `http://192.168.1.20:8765`. Use **Copy** and send one to your players.
3. Players open the address on any phone, tablet or computer on the **same network**, choose
   their character and enter its password.
4. The sheet is view only. Whenever you change something in the app (damage, spell slots, XP,
   items...) every open sheet updates within a second or two.
5. Click **Stop local session** when you're done. Closing the app or switching campaigns stops
   it as well.

If players can't connect, allow Almerion through the Windows firewall for **private networks**
(Windows asks the first time you start a local session), and make sure everyone is on the same
Wi-Fi rather than a guest network. After five wrong passwords a device is locked out for a
minute.

### Over the internet (players use the app)

The data is AES-256 encrypted.

**GM:**

1. Sign in online as a verified campaign GM, then click **Start internet session** under
   **Over the internet**. A strong random encryption key is generated automatically.
2. Members can now click **Join Live Session**. You can also give the **Player Token** to
   someone who needs to join by token.
3. The campaign is pushed automatically each time you return to the GM menu. **Push State Now**
   sends it immediately.
4. Click **Stop internet session** when you're done.

Only the verified host can write or delete the shared snapshot; the player token is read only
for campaign state. Guest chat has its own limited mailbox access, not host permissions.
Token access expires 12 hours after the last push. Old-format tokens no longer work: update all
devices and start a new session after the administrator completes the cloud migration.
If your old app account is not yet listed in Firebase Authentication, choose **Create an
account** using your existing username and the administrator-confirmed email. Verify the
emailed link, then log in with that email. This claims your reserved identity and existing
campaign membership; do not create a replacement campaign.

**Player:** in **My Campaigns**, click **Join Live Session**. Or open
*Join with a session token instead*, paste the token and click **Join Session**.
**Go to My Character** opens your sheet, and **Leave Session** disconnects.

### Private and group session chat

Open **Session chat** from the GM menu, Notifications, your desktop character home, or
your hosted browser sheet. Choose recipients under **New conversation**, optionally name
the conversation, then send messages. Groups keep their original members; create another
conversation to change the audience. The badge counts unread messages; opening a
conversation marks it read.

**The current hosting GM can read every conversation, including player-only private and
group chats.** This is disclosed in every chat panel. Monitoring does not let the GM send
as another player or send into a conversation they have not joined.

Browser-link and desktop-token guests use their character password, without creating an
account. Verified campaign members can chat as their assigned character without another
password. **Use character password** switches a desktop chat to account-free guest access,
including when an online account is not a campaign member. Simultaneous local and internet
hosting shares the same conversations.

Chats are session-only: the latest **500 messages per conversation** stay in host memory,
and disappear when the final hosting transport stops. They are not written into campaign
files, exports, backups or action history. Internet mailboxes use separate per-connection
encryption; the player sheet token cannot decrypt another player's chat. Normal stop
removes the encrypted cloud mailboxes. A crash or failed cleanup can leave encrypted
residue, but the ephemeral host key is not saved. Previously read or copied messages
cannot be recalled. Local HTTP hosting is for trusted private networks, not public Wi-Fi.

If delivery is uncertain, use **Retry pending action** rather than sending again. Retry
reuses the request ID to avoid duplicate messages. **Discard pending retry** warns that
the original delivery may already have succeeded. Safety limits are 2,000 characters per
message, 100 conversations per session, 32 selected recipients, 32 desktop chat connections
per verified account, and 32 account-free desktop guest connections per hosted session.
Keep tokens private. To revoke a guest, change the character password; stop/restart internet
hosting to invalidate all old tokens. Guest password throttling is shared per character.

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
and death saves are left as empty boxes to fill in with a pencil. Money is printed with inventory.

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

GMs can also edit **Personal Features** on an individual character without changing
everyone in their class. These use the same level/subclass filters and appear in combat,
player profiles, LAN sheets and PDFs. Unrecognised feature names remain descriptive reminders.
Zurabaach gives Darius **Brawler's Throw** (replace an attack with a GM-resolved grapple
throw; no damage) and **Pit Guardian** (reaction, +2 ally AC until their next turn,
once per short rest). Balthazar gets **Encore**: after an ally successfully spends his
Inspiration, a reaction heals proficiency-bonus HP, once per short rest. The map tracks
uses and effects; the GM confirms reaction availability, ally status and roll success.
Encore requires his active Inspiration marker and cannot revive a downed ally.
Update every device before syncing campaigns with personal features: older app versions
do not preserve this new field when saving characters.

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

**GitHub sync says it cannot lock the campaign index (`index.lock`):** another Almerion
window or Git program may be using the same campaign, or a previous crash may have left a
lock behind. Close other programs using that campaign and try **Sync Now** again. If the
lock remains, choose **Review index lock...** in the sync window or error dialog. Continue
only after confirming no other Git operation is running. Almerion backs up the lock inside
the campaign's `.git\almerion-lock-backups` folder and retries; it does not delete your
campaign files. Never remove a lock just because it is old.

The small **🐞** button is available on app screens and dialogs. Choose **Bug** or
**Suggestion**, describe the issue, and review the automatically captured app screenshot.
Uncheck **Include screenshot** to leave it out. Nothing is sent until you click **Send**.
The recipient defaults to the configured password-recovery sender address and can be edited.
SMTP credentials are never included in the report. If SMTP is unavailable, **Save email
draft…** writes an `.eml` file with the optional screenshot attached; open it in a compatible
email application and send it there.

| Problem | What to do |
|---|---|
| **No verification or reset email** | Check spam and the Firebase email/password setup. Online verification/reset uses emailed links, never an on-screen bypass. SMTP **Test email settings** applies only to local-code accounts and outgoing app reports. |
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
