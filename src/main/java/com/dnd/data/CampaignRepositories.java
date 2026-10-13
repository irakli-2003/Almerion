package com.dnd.data;

import com.dnd.data.dto.MapCatalog;
import com.dnd.model.world.map.GameMap;
import com.dnd.data.dto.BeastCatalog;
import com.dnd.data.dto.CharacterClassCatalog;
import com.dnd.data.dto.CharacterRaceCatalog;
import com.dnd.data.dto.DamageTypeCatalog;
import com.dnd.data.dto.EffectCatalog;
import com.dnd.data.dto.ItemCatalog;
import com.dnd.data.dto.MonsterCatalog;
import com.dnd.data.dto.NpcCatalog;
import com.dnd.data.dto.PlaceCatalog;
import com.dnd.data.dto.PlayerRoster;
import com.dnd.data.dto.SpellCatalog;
import com.dnd.data.dto.LanguageCatalog;
import com.dnd.data.dto.AlchemyIngredientCatalog;
import com.dnd.data.dto.BookCatalog;
import com.dnd.data.dto.DiceCatalog;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.combat.DamageType;
import com.dnd.model.creature.Beast;
import com.dnd.model.creature.Monster;
import com.dnd.model.creature.Npc;
import com.dnd.model.item.Item;
import com.dnd.model.combat.Effect;
import com.dnd.model.magic.Spell;
import com.dnd.model.world.Place;
import com.dnd.model.alchemy.AlchemyIngredient;
import com.dnd.model.item.books.Book;
import com.dnd.model.world.Language;
import com.dnd.model.world.Dice;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

public class CampaignRepositories {
    private final CampaignPaths paths;
    private final ObjectMapper mapper;

    private JsonRepository<CharacterClass, CharacterClassCatalog> classes;
    private JsonRepository<CharacterRace, CharacterRaceCatalog> races;
    private JsonRepository<Item, ItemCatalog> items;
    private JsonRepository<Spell, SpellCatalog> spells;
    private JsonRepository<Place, PlaceCatalog> places;
    private JsonRepository<Effect, EffectCatalog> effects;
    private JsonRepository<DamageType, DamageTypeCatalog> damageTypes;
    private JsonRepository<Npc, NpcCatalog> npcs;
    private JsonRepository<Monster, MonsterCatalog> monsters;
    private JsonRepository<Beast, BeastCatalog> beasts;
    private JsonRepository<PlayerCharacter, PlayerRoster> players;
    private JsonRepository<Language, LanguageCatalog> languages;
    private JsonRepository<AlchemyIngredient, AlchemyIngredientCatalog> alchemyIngredients;
    private JsonRepository<Book, BookCatalog> books;
    private JsonRepository<Dice, DiceCatalog> dice;
    private JsonRepository<GameMap, MapCatalog> maps;

    public CampaignRepositories(Path campaignRoot) {
        this.paths = new CampaignPaths(campaignRoot);
        this.mapper = JsonMappers.create();
    }

    /** Folder holding this campaign's data; relative image paths resolve against it. */
    public Path campaignRoot() {
        return paths.getRoot();
    }

    public JsonRepository<CharacterClass, CharacterClassCatalog> classes() {
        if (classes == null) {
            classes = new JsonRepository<>(
                paths.classesFile(),
                mapper,
                CharacterClassCatalog::new,
                CharacterClassCatalog.class,
                CharacterClassCatalog::getClasses,
                CharacterClassCatalog::setClasses,
                CharacterClass::getId
            );
        }
        return classes;
    }

    public JsonRepository<CharacterRace, CharacterRaceCatalog> races() {
        if (races == null) {
            races = new JsonRepository<>(
                paths.racesFile(),
                mapper,
                CharacterRaceCatalog::new,
                CharacterRaceCatalog.class,
                CharacterRaceCatalog::getRaces,
                CharacterRaceCatalog::setRaces,
                CharacterRace::getId
            );
        }
        return races;
    }

    public JsonRepository<Item, ItemCatalog> items() {
        if (items == null) {
            items = new JsonRepository<>(
                paths.itemsFile(),
                mapper,
                ItemCatalog::new,
                ItemCatalog.class,
                ItemCatalog::getItems,
                ItemCatalog::setItems,
                Item::getId
            );
        }
        return items;
    }

    public JsonRepository<Spell, SpellCatalog> spells() {
        if (spells == null) {
            spells = new JsonRepository<>(
                paths.spellsFile(),
                mapper,
                SpellCatalog::new,
                SpellCatalog.class,
                SpellCatalog::getSpells,
                SpellCatalog::setSpells,
                Spell::getId
            );
        }
        return spells;
    }

    public JsonRepository<Place, PlaceCatalog> places() {
        if (places == null) {
            places = new JsonRepository<>(
                paths.placesFile(),
                mapper,
                PlaceCatalog::new,
                PlaceCatalog.class,
                PlaceCatalog::getPlaces,
                PlaceCatalog::setPlaces,
                Place::getId
            );
        }
        return places;
    }

    public JsonRepository<Effect, EffectCatalog> effects() {
        if (effects == null) {
            effects = new JsonRepository<>(
                paths.effectsFile(),
                mapper,
                EffectCatalog::new,
                EffectCatalog.class,
                EffectCatalog::getEffects,
                EffectCatalog::setEffects,
                Effect::getId
            );
        }
        return effects;
    }

    public JsonRepository<DamageType, DamageTypeCatalog> damageTypes() {
        if (damageTypes == null) {
            damageTypes = new JsonRepository<>(
                paths.damageTypesFile(),
                mapper,
                DamageTypeCatalog::new,
                DamageTypeCatalog.class,
                DamageTypeCatalog::getDamageTypes,
                DamageTypeCatalog::setDamageTypes,
                DamageType::getId
            );
        }
        return damageTypes;
    }

    public JsonRepository<Npc, NpcCatalog> npcs() {
        if (npcs == null) {
            npcs = new JsonRepository<>(
                paths.npcsFile(),
                mapper,
                NpcCatalog::new,
                NpcCatalog.class,
                NpcCatalog::getNpcs,
                NpcCatalog::setNpcs,
                Npc::getId
            );
        }
        return npcs;
    }

    public JsonRepository<Monster, MonsterCatalog> monsters() {
        if (monsters == null) {
            monsters = new JsonRepository<>(
                paths.monstersFile(),
                mapper,
                MonsterCatalog::new,
                MonsterCatalog.class,
                MonsterCatalog::getMonsters,
                MonsterCatalog::setMonsters,
                Monster::getId
            );
        }
        return monsters;
    }

    public JsonRepository<Beast, BeastCatalog> beasts() {
        if (beasts == null) {
            beasts = new JsonRepository<>(
                paths.beastsFile(),
                mapper,
                BeastCatalog::new,
                BeastCatalog.class,
                BeastCatalog::getBeasts,
                BeastCatalog::setBeasts,
                Beast::getId
            );
        }
        return beasts;
    }

    public JsonRepository<PlayerCharacter, PlayerRoster> players() {
        if (players == null) {
            players = new JsonRepository<>(
                paths.playersFile(),
                mapper,
                PlayerRoster::new,
                PlayerRoster.class,
                PlayerRoster::getPlayers,
                PlayerRoster::setPlayers,
                PlayerCharacter::getId
            );
        }
        return players;
    }

    public JsonRepository<Language, LanguageCatalog> languages() {
        if (languages == null) {
            languages = new JsonRepository<>(
                paths.languagesFile(),
                mapper,
                LanguageCatalog::new,
                LanguageCatalog.class,
                LanguageCatalog::getLanguages,
                LanguageCatalog::setLanguages,
                Language::getId
            );
        }
        return languages;
    }

    public JsonRepository<AlchemyIngredient, AlchemyIngredientCatalog> alchemyIngredients() {
        if (alchemyIngredients == null) {
            alchemyIngredients = new JsonRepository<>(
                paths.alchemyIngredientsFile(),
                mapper,
                AlchemyIngredientCatalog::new,
                AlchemyIngredientCatalog.class,
                AlchemyIngredientCatalog::getIngredients,
                AlchemyIngredientCatalog::setIngredients,
                AlchemyIngredient::getId
            );
        }
        return alchemyIngredients;
    }

    public JsonRepository<Book, BookCatalog> books() {
        if (books == null) {
            books = new JsonRepository<>(
                paths.booksFile(),
                mapper,
                BookCatalog::new,
                BookCatalog.class,
                BookCatalog::getBooks,
                BookCatalog::setBooks,
                Book::getId
            );
        }
        return books;
    }

    public JsonRepository<Dice, DiceCatalog> dice() {
        if (dice == null) {
            dice = new JsonRepository<>(
                paths.diceFile(),
                mapper,
                DiceCatalog::new,
                DiceCatalog.class,
                DiceCatalog::getDice,
                DiceCatalog::setDice,
                Dice::getId
            );
        }
        return dice;
    }

    public JsonRepository<GameMap, MapCatalog> maps() {
        if (maps == null) {
            maps = new JsonRepository<>(
                paths.mapsFile(),
                mapper,
                MapCatalog::new,
                MapCatalog.class,
                MapCatalog::getMaps,
                MapCatalog::setMaps,
                GameMap::getId
            );
        }
        return maps;
    }
}
