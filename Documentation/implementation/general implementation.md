# General Implementation Overview

## Purpose
This document summarizes how the current Almerion system is implemented, with emphasis on the CLI. Detailed flow notes live in `Documentation/implementation/user flow.md`.

## Status Snapshot (Current Code)
- **CLI mode** is implemented and is the active entry point.
- **UI mode** is not implemented yet (placeholder only).
- **Entity CRUD** (create, edit, delete, open) is implemented in the CLI for multiple entity types.

## Architecture Overview
- **Pattern**: Page Object Model (POM). Each page provides title/body text and a command list.
- **Controller**: `CliApp` wires pages and runs the render/input/transition loop.
- **Core types**: `com.dnd.cli.core.Page`, `CommandSpec`, `CommandAction`, `CommandResolver`, `CliSession`.

## CLI Runtime Behavior
- The CLI loop renders the current page (title, body, commands) and waits for input.
- Input is trimmed and case-insensitive.
- Commands accept **unique prefixes** (e.g., `ex` for `exit`). Ambiguous prefixes are invalid.
- Global commands:
  - `exit` terminates the program.
  - `back` or `b` returns to the parent page (if any).
- Invalid input prints `invalid command` and re-prompts.

## Page Graph (Implemented)
- **Landing Page**: `dm` -> Campaign Selection, `player` -> placeholder page.
- **Campaign Selection**:
  - `create` -> Create Campaign
  - `rename` -> rename a campaign (with uniqueness handling)
  - `delete` -> delete a campaign (requires `DELETE` confirmation)
  - selecting a campaign name -> DM Menu
- **Create Campaign**:
  - `default` -> copy default campaign template
  - `blank` -> same structure, empty arrays
- **DM Menu**:
  - `create`, `edit`, `delete`, `open` -> Entity Selection pages
- **Entity Selection**:
  - Choose a type, then CRUD actions based on the current operation.

## Entity Types (CLI CRUD)
The CLI supports the following entity types (key -> label -> file):
- `class` -> Class -> `world/classes.json`
- `race` -> Race -> `world/races.json`
- `item` -> Item -> `world/items.json`
- `spell` -> Spell -> `world/spells.json`
- `place` -> Place -> `world/places.json`
- `effect` -> Effect -> `world/effects.json`
- `damage-type` -> Damage Type -> `world/damage-types.json`
- `beast` -> Beast -> `world/beasts.json`
- `monster` -> Monster -> `world/monsters.json`
- `npc` -> NPC -> `world/npcs.json`
- `language` -> Language -> `world/languages.json`
- `alchemy` -> Alchemy Ingredient -> `world/alchemy-ingredients.json`
- `book` -> Book -> `world/books.json`
- `player` -> Player -> `players/players.json`

## Entity CRUD Behavior (Implementation Notes)
- Entity creation/editing is **reflection-based** via Java bean getters/setters.
- Supported field types:
  - `String`, numeric primitives/wrappers, `boolean`, enums
  - `List<String>`
  - `CoreStats` (special prompt for STR/DEX/CON/INT/WIS/CHA)
- **Nested objects** (non-primitive, non-enum) prompt recursively for their fields; lists of complex objects prompt for entry count and then walk each entry.
- **Maps** with `String` keys and `String`/`int` values prompt for key/value entries (used by `savingThrowBonuses`, `abilityBonuses`, and `dictionary`).
- Unsupported field types are skipped and logged as `Skipping <field> (unsupported type).`
- During edit, blank input keeps the existing value; nested objects allow a per-field edit pass.
- **Option lists** are provided for common foreign-key fields:
  - `classId`, `raceId`, `itemId`, `spellId`, `languages`
  - Users can enter an index or a custom value.
- **Item creation** prompts for a concrete subtype (armor, book, alchemy items, physical/magic weapons) before collecting fields.
- Delete and campaign deletion require a second confirmation (`DELETE`).

## Campaign Storage Layout
- Data root resolution:
  - Uses `src/main/resources/data` when present (dev mode).
  - Falls back to `data` when packaged (runtime mode).
- Default template: `default-campaign/`
- Custom campaigns: `custom-campaigns/<campaign-name>/`
- Campaign name normalization:
  - Lowercase, whitespace and invalid chars -> `-`
  - Only `a-z`, `0-9`, `_`, `-` are preserved
  - Unique names are auto-suffixed (`name-2`, `name-3`, ...)

## Campaign Template Contents
Each campaign has:
- `world/`
  - `classes.json`, `races.json`, `items.json`, `spells.json`, `places.json`
  - `effects.json`, `damage-types.json`, `npcs.json`, `monsters.json`, `beasts.json`
  - `languages.json`, `alchemy-ingredients.json`, `books.json`, `id-registry.json`
- `players/`
  - `players.json`

## JSON Data Guidelines
- **Descriptive values** are strings (name, description).
- **Logic-driven values** are numeric or boolean (stats, bonuses, effects).
- **Collections** use arrays of nested objects.
- **Stats** use a structured stat block (strength, dexterity, constitution, intelligence, wisdom, charisma).
- **Enums** such as habitat and challenge rating are stored as strings.

## Data Model and Persistence
- Model classes live under `src/main/java/com/dnd/model/`.
- JSON persistence uses repositories that support CRUD:
  - `com.dnd.data.JsonRepository` for file I/O and list management.
  - `com.dnd.data.CampaignRepositories` exposes per-entity repositories.
- `com.dnd.data.CampaignPaths` defines canonical file locations for each entity.

## Spell Model Notes
- `Spell.school` uses `com.dnd.model.magic.School`.
- `Spell.castingMethod` uses `com.dnd.model.magic.CastingMethod`.
- `Spell.effects` is a list of `com.dnd.model.combat.Effect` objects.
- `Spell.damage` uses `com.dnd.model.combat.Damage`.
- `Spell.concentration` uses `com.dnd.model.magic.Concentration`.

## Id Registry
- Registry lives at `world/id-registry.json`.
- `com.dnd.data.IdHandler`:
  - Generates normalized, unique ids from names.
  - Stores file ownership metadata (relative path).
  - Saves best-effort; failures are tolerated.

## Packaging and Distribution
- Maven builds a standard JAR with `com.dnd.cli.CliApp` as the main class.
- Dependencies are not shaded by default; use the Maven build/run instructions in `README.md`.

## Documentation Structure (Current)
- `Documentation/implementation/general implementation.md`: this overview.
- `Documentation/implementation/user flow.md`: detailed CLI flow notes.
- Future documentation folders (`cli/`, `pages/`, `routing/`, `domain/`, `persistence/`) are planned but not present yet.

## Known Issues

### ~~Dice deserialization breaks Class catalog loading~~ (FIXED)
- **Status**: Fixed with Jackson @JsonCreator support
- **Resolution**: 
  - Added @JsonCreator static factory method fromSides() to com.dnd.model.world.Dice
  - The class now accepts both numeric values (e.g., "hitDie: 10) and full objects
  - When deserializing from a number, the factory method automatically generates id and name as d{sides} format


