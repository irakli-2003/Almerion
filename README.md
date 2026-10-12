# DnD
Structured environment for running Dungeons and dragons campaign

## CLI quick start
Build and run the CLI entry point:

```powershell
mvn test
mvn package
java -jar target\dnd-campaign-manager-1.0.0.jar
```

To launch the JavaFX UI directly from Maven:

```powershell
mvn javafx:run
```

### Campaign storage
- `src/main/resources/data/default-campaign/` holds the template campaign (read-only in CLI).
- `src/main/resources/data/custom-campaigns/` stores one folder per custom campaign.
- `src/main/resources/data/srd/` holds the standard-rules dataset that new campaigns are
  seeded from (see below).

### Standard rules dataset
Creating a campaign without ticking **Start blank** seeds it from the bundled standard-rules
catalogues in `src/main/resources/data/srd/`, so you can start playing with conventional
D&D-style content instead of an empty world:

| Catalogue | Entries | Contents |
| --- | --- | --- |
| `races.json` | 21 | The core ancestries plus their common subraces |
| `classes.json` | 12 | Hit die, primary abilities and saving-throw bonuses per class |
| `items.json` | 94 | 41 weapons (incl. 5 magic weapons), 21 armours and shields, 12 potions and oils, 20 pieces of adventuring gear |
| `spells.json` | 67 | Cantrips through 9th level, with school, casting method, range and mana cost |
| `monsters.json` | 59 | CR 0 to CR 24, from goblins to an ancient red dragon |
| `beasts.json` | 26 | Animals and mounts by habitat |
| `npcs.json` | 22 | Ready-to-use townsfolk, patrons and villains |
| `places.json` | 18 | Settlements, dungeons and landmarks, most linked to a battle map |
| `maps.json` | 12 | Grid battle maps (see below) |
| `effects.json` | 34 | Conditions and ongoing effects, including the six the damage engine looks up by id |
| `damage-types.json` | 15 | Physical and elemental damage types |
| `languages.json` | 18 | Each with its primer book and study time |
| `books.json` | 26 | 18 language primers plus 8 pieces of in-world lore |
| `alchemy-ingredients.json` | 24 | Reagents for brewing |
| `dice.json` | 7 | d4 through d100 |
| `players.json` | 6 | A pregenerated level 3 party, already equipped |

Bundled battle maps: the Gilded Stag tavern (common room and guest rooms), Greenhollow's
village square, the Ironhand smithy, the temple of the Dawnfather, Stonekeep's ground floor,
a standing-stone forest clearing, the Barrow Crypt, Blackfen Cave, a roadside bandit camp,
Magister Orin's tower, and a blank open-ground map for improvised fights. Impassable cells
(walls, furniture, deep water) render as black in the map editor.

A default campaign also gets a starter `storyline/` chapter with three session files that
demonstrate the `[READ ALOUD]` / `[DM NOTE]` markers and map links.

Ticking **Start blank** still produces empty catalogues for every one of these files.

The dataset is original text written for this project, describing rules concepts from the
System Reference Document (CC-BY-4.0); no SRD wording is reproduced verbatim.

### Editing stat blocks

The world-building screens edit the whole entity, not just its name and description. Alongside
plain text and number fields the editor renders:

- **ability scores** as a six-cell grid with the derived modifier shown next to each score;
- **enums** (challenge rating, spell school, habitat, armour material) as drop-downs;
- **id references** (class, race, damage type, item, spell) as name drop-downs, and id *lists*
  (languages, triggered effects) as tick lists, so no raw id ever has to be typed;
- **key/value blocks** (racial ability bonuses, class saving throws, place attributes,
  language dictionaries) as editable rows;
- **nested objects** (hit die, damage, durability, concentration) inline;
- **lists of objects** (monster abilities, carried items, known spells) with add/remove.

An id that no longer exists in its catalogue is shown as `(missing)` and preserved rather than
silently dropped. The catalogue list also shows a one-line digest per entry - challenge rating
and ability scores for monsters and beasts, role and level for NPCs, level/race/class for
player characters, level and school for spells, type/weight/value for items.

### Commands
- `dm` or `player` at the landing page.
- `create` in campaign selection to create a new campaign.
- `back` or `b` to go back.
- `exit` to quit.

## Accounts & email setup (GUI)
Launching the GUI (`com.dnd.ui.GuiApp`) now requires logging in before the Player/DM
choice is shown. Accounts, registration codes, and password-reset codes are stored
locally at `data/auth/users.json` (never committed - see `.gitignore`).

Verification and password-recovery emails are sent over SMTP once you fill in
`data/auth/smtp-config.json` (auto-created with placeholders on first run):

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

Until `enabled` is set to `true` with real credentials, the app falls back to showing
the one-time verification/reset code directly in a dialog, so registration and
password recovery remain fully usable without any email provider configured.

### Troubleshooting email

Use the **Test email settings** button on the login screen. It sends a real message to
`fromAddress` and reports the exact failure instead of a generic "not configured" notice.
The same real reason is now shown in the code-fallback dialog whenever a send fails.

Common causes:

- **`host` must be a mail server, not an address** - `smtp.gmail.com`, not `you@gmail.com`.
- **Gmail requires an App Password**, not your account password. Enable 2-Step Verification,
  then create one at <https://myaccount.google.com/apppasswords>. Google shows it as
  `abcd efgh ijkl mnop`; the spaces are display-only and are stripped automatically.
- **Connection timed out / no route to host** - your network is blocking outbound SMTP.
  Office, university, and VPN networks very commonly block ports 587, 465 and 25. This is
  *not* a configuration error: the credentials can be perfectly correct and the connection
  still never opens. Try port `465` (implicit SSL, selected automatically when you set
  `"port": 465`), or run the app from a home network or phone hotspot. Nothing in the app
  can work around a blocked port.
- With email unavailable for any reason, the in-app code dialog still lets you finish
  registering or resetting a password.

## Storyline (DM menu)

The Storyline section pairs a file tree of session notes (`<campaign>/storyline/`) with a
horizontally scrollable story-arc timeline. Sessions are the text files; folders become
half-circle arcs spanning their sessions, so the root folder arcs across the whole campaign.

- **Ordering** is explicit and persisted per folder in a hidden `.storyline-order` file.
  Drag items in the tree, or use the arrow buttons, to reorder. The arc timeline reads the
  same order, so the two views never disagree.
- **Editor** opens maximized and fills the window. Its toolbar has:
  - `Insert Info` - a cascading menu of every campaign catalog (NPCs, monsters, items,
    spells, races, ...); hover a category to get a filterable, scrollable list of its
    entries, and click one to insert a formatted block about it at the caret.
  - `Insert Block` - templates for a scene, encounter, NPC beat, skill check, loot, or
    session recap.
  - `Read-Aloud` / `DM Note` - wrap the selection in `[READ ALOUD]` or `[DM NOTE]` markers,
    keeping player-facing prose separate from private prep.
  - `Player View` - a large-type, read-only window showing only the read-aloud passages,
    for reading straight to the table.
  - `Insert Map` - drops a `[map:<id>|<label>]` link for any map in the campaign. Double-click
    a link (or put the caret in it and press `Open Map Link`) to launch the battle map.
  - A session timer that counts up from zero or down from a set number of minutes.
  - Heading, bullets, checklist, divider, and `Ctrl+S` to save.
  - `Find` (`Ctrl+F`) - a floating find bar with Previous/Next, match case and an
    "n of m" counter; Enter / Shift+Enter step through matches, Esc closes.
  - `Notes` - a small floating scratchpad shared by every session file of the campaign
    (`<campaign>/notes.txt`), autosaved as you type. Handy for running trackers.
  - `Manage Players` - toggles a full-height panel on the right with a card for every player
    character plus any monsters, NPCs or beasts added to this session (`+ Add`). Cards show
    HP, mana and active effects, with quick `−` / `+` buttons that damage or heal by the
    chosen amount. Click a card for details (vitals, items, effects, rests and level-ups),
    and `← Back` to return to the list. Added creatures are separate copies with their own
    HP, saved per session file in a hidden `.<file>.roster.json` next to it.
    No popups: `+ Add`, `+ Add Item` and `+ Add Effect` open a searchable list right inside
    the panel (type, then click or press Enter; it stays open to add several). `✚ New item...`
    / `✚ New effect...` create a catalog entry on the spot and apply it in one step. Effects
    have `−` / `+` round buttons. `★ XP` (or the toolbar's `Award XP`) awards XP to the whole
    party or the ticked players at once.

Read-aloud markers and map links are stripped from the Player View, so the table only ever
sees the prose.

## Images folder

Keep portraits, map backgrounds, textures, etc. in the `images/` folder at the project root
(subfolders `characters`, `maps`, `textures`, `monsters`, `items`). Every image picker in the
app opens there; the chosen file is still copied into the campaign as before.

## Battle map

The battle map is the playground where encounters are run. Open it from a `[map:...]` link in
a session file, or from `Run Battle` in the map viewer.

- **Turn order.** `Roll Initiative` rolls d20 + DEX for every creature on the map and sorts
  them; `Next Turn` cycles forever, skipping the dead and bumping the round counter on each
  wrap. The roster shows the current combatant highlighted with the next few queued behind it.
- **Token detail.** Click any token to swap the left panel into its sheet - portrait, stats,
  health and mana bars, coins, items, spells, and abilities - with `← Back` to return to the
  roster. Click empty space or press `Esc` to deselect.
- **Vitals.** Damage, healing, max HP/mana, coins, and initiative are all editable in place.
  Dropping to zero hit points knocks a creature down rather than killing it; from there
  `Failed Save` counts 3 -> 2 -> 1 on the token in red, and the third failure marks it dead
  with a red cross. `Made Save` and healing above zero bring it back.
- **Ranges.** Hovering a spell or ability draws its range as a disc around the token (5 ft per
  cell) and rings every creature inside it, so you can see legal targets before committing.
- **Map control.** Add and remove tokens, drag them between cells, toggle a cell impassable,
  show or hide health bars, zoom, and save the map back to the campaign. A timer is available
  here too.

### Class & race features

Characters get the basic features of their class (up to their level, plus their subclass's)
and their race, and those features do things in battle rather than just sitting on the sheet.
They appear in the battle-map token detail and in Manage Players under **Class & Race
Features**, with uses left and what brings them back.

- **Armor Class** is worked out from equipped armor (or Unarmored Defense: barbarian
  10 + DEX + CON, monk 10 + DEX + WIS), shields, worn pieces, and active effects. Hover it for
  the breakdown.
- **Usable features** have buttons. Self features apply at once, e.g. Rage gives +2/+3/+4
  melee damage and halves bludgeoning, piercing, and slashing damage for 10 rounds, and Second
  Wind rolls 1d10 + level healing. Targeted features (Lay on Hands, Bardic Inspiration,
  Breath Weapon) arm the cursor so you can click a token.
- **Resistances** (Rage, tiefling fire, dwarf poison, ...) halve typed damage automatically.
  Pick the damage type next to the Damage button. Relentless Endurance (half-orc) and Undying
  (revenant) drop the character to 1 HP instead of 0 once per long rest.
- **Attacks.** Each weapon and an unarmed strike is listed with to-hit and damage. Click one,
  then the target. The form shows d20 + bonus vs. the target's AC. You enter the rolled damage
  dice and the app adds STR/DEX, Rage, Sneak Attack, and Fury of the Small. Firearms keep using
  their ammo spells.
- **Rests.** A short rest restores Second Wind, Action Surge, Ki, Channel Divinity, Wild Shape,
  Breath Weapon, and similar features. A long rest restores everything.
- New characters get their race's ability bonuses added to their scores. Existing sheets are
  treated as final.

### Artificer: energy charges, blueprints and the workshop furnace

The **Artificer** is a homebrew half caster. It uses **energy charges** instead of spell slots.
The counts are the same as a half caster's slots, but each level is a type of charge:

| Level | Charge | Fuel units per charge |
|---|---|---|
| 1 | Steam-pressure | 2 |
| 2 | Thermal | 4 |
| 3 | Electric | 7 |
| 4 | Magnetic | 10 |
| 5 | Resonance | 14 |

- **Charges are made at a workshop furnace**, which is a *Tool*
  item with a furnace tier. The tier is the highest charge level it can make. The Tinkerer's
  Portable Forge is tier 1 and the Valebrick Portable Field Workshop is tier 2.
- **The furnace burns fuel.** Each *Fuel* item has a value: firewood 2, charcoal 3, coal 4,
  lamp oil 5. That many units go into the furnace per piece; a charge costs the units in the
  table. For example, one coal (4) makes two Steam-pressure charges or one Thermal charge.
- **A long rest refuels automatically** if the artificer carries a furnace and fuel. Missing
  charges are refilled from the lowest level up to the furnace tier. The weakest fuel burns
  first, and fuel is only burned when it can finish a charge. A **short rest** refuels too, but
  only fills each charge type up to half of its maximum (rounded up), weakest first.
- **Mechanical devices** take a charge of their level or any stronger one. Each level above
  the spell's level adds one damage die. **Tuned devices** run only on their own capsule type.
  For example, the Thermal Smoke Grenade works only with a Thermal charge.
- **Crafting** counts as a mechanical action, so any charge of the required level or stronger works.
- **Blueprints are an item type.** A blueprint lists:
  - its product;
  - its materials, which are used up;
  - its tools, which are kept;
  - the charges it needs, if any;
  - a minimum level.

  Copy a carried blueprint into the **blueprint book**, then build from it whenever you have the
  parts.
- **Devices are an item type.** A device can link a spell. Carrying it adds the spell to the
  token, and the spell's level decides which charge it uses (level 0 needs no charge).
  Ammunition and grenades are devices whose spell uses up one of them.
- **There are 13 bundled blueprints:**
  - Utility and control: Smoke Canister, Tripwire Alarm, Steam Grapnel Launcher, Sticky Foam,
    Messenger Beetle, Thermal Smoke Grenade (Thermal only).
  - Light combat: Scalding Bolts, Flash Bomb, Shock Darts, Steam-Jet Gauntlet.
  - Medium combat: Thermal Grenade, Pressure Ram, Concussion Bolts.
- **The Workshop section in Manage Players** shows charges and furnace fuel, and has buttons to:
  - burn a fuel;
  - make a charge;
  - copy a blueprint into the book;
  - build a known blueprint. When something is missing, the button explains what.
- **New item kinds:** Blueprint, Device, Tool, Material and Fuel. You can create any of them
  from the DM panel or from the Manage Players **+ New item** form. Both show the kind's own
  fields.
- **Toren Valebrick** (Human Artificer 5, Artillerist) is in the zurabaach party. He has the
  field workshop, the Bolt Repeater, materials and fuel.

### Creation help and class defaults

Every option in the character wizard and the DM creation forms (class, race, character, spell,
item) has a short note explaining what it does. Choices from a list show a live explanation
of the selected value. On the wizard's Subclass step, each subclass has an overview of its theme,
play style and who it suits. Below that is every subclass feature by level, with what it does.
The DM can edit these overviews in the class form (*Subclass Descriptions*).

New player characters are created only through the **Create Character** wizard. The DM menu's
Players section and the *New* button in the player list both open it.

Each class has default spells and items. When you pick a class in the wizard, its defaults are
pre-selected, and you can still change them.

## Background music

Music comes from **YouTube links** and plays only on the DM's computer. Open it with **🎵 Music** in the DM menu, the battle map toolbar, or the session text editor.

- **Playlists:** create one (e.g. *Tavern*, *Dungeon*, *Boss fight*), then **+ Add link** and paste any YouTube video or playlist URL. Add `&t=1m30s` to start partway in. Double-click a track to play it. Switching playlists crossfades.
- **Combat music:** choose a *⚔ Combat playlist*. When initiative is rolled or entered on the battle map, the music crossfades to it, and it switches back when the map closes. You can turn this off in the map's 🎵 menu.
- **Map music:** on the battle map, choose 🎵 → *This map's music* to play a playlist whenever that map opens.
- **Starter pack:** a campaign without its own music starts with ready-made playlists of popular YouTube videos: Tavern, City market, Harbor, Castle, Village, Travel, Forest, Dungeon, Mystery, Horror, Combat, Boss fight, Sad, Camp, At sea. It also has ambience and sound effects. Campaigns that already have music get it with **★ Starter pack**, which only adds what's missing.
- **Ambience:** looping background sounds (river, wind, rain, thunderstorm, fireplace, cave, swamp...) play under the music. Click one to start it and click it again to stop it. Tick *Ambience* when adding a sound to make it loop.
- **Sound effects:** add a YouTube clip with optional start/end seconds, then click its pad button to play it once. Music is turned down while the effect plays.
- **Session cues:** in a session text file, write `[music: Playlist name]`, `[music: stop]`, `[sfx: Sound name]` or `[sfx: stop]`, or insert them from the editor's 🎵 menu. Double-click a cue to play it.

Everything is saved to `music.json` in the campaign folder. The first time you play, the app downloads an embedded Chromium player (~150 MB) to `~/.dnd-campaign-manager/jcef`. A small player window appears briefly and then hides. Videos whose owners block playback outside YouTube are skipped automatically.