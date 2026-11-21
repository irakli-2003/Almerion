package com.dnd.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Structured contents of a GM-only lore book. */
public final class LoreBook {
    private static final ObjectMapper MAPPER = JsonMappers.create()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String title = "Lore book";
    public List<Section> sections = new ArrayList<>();

    public static final class Section {
        public String title;
        public List<Article> articles = new ArrayList<>();

        public Section() { }
        public Section(String title) { this.title = title; }
    }

    public static final class Article {
        public String title;
        public String text;

        public Article() { }
        public Article(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    public static LoreBook parse(String json) {
        if (json == null || json.isBlank()) return new LoreBook();
        try {
            LoreBook book = MAPPER.readValue(json, LoreBook.class);
            if (book == null) throw new IllegalArgumentException("The lore book is empty.");
            book.validate();
            return book;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The lore book is unreadable.", e);
        }
    }

    public String serialize() {
        validate();
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode the lore book.", e);
        }
    }

    private void validate() {
        if (title == null || title.isBlank() || sections == null) {
            throw new IllegalArgumentException("The lore book needs a title and sections.");
        }
        for (Section section : sections) {
            if (section == null || section.title == null || section.title.isBlank() || section.articles == null) {
                throw new IllegalArgumentException("A lore section is invalid.");
            }
            for (Article article : section.articles) {
                if (article == null || article.title == null || article.title.isBlank() || article.text == null) {
                    throw new IllegalArgumentException("A lore article is invalid.");
                }
            }
        }
    }
}
