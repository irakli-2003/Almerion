package com.dnd.ui.i18n;

import java.io.IOException;

/** Machine translation from English into another language. Implementations may block. */
public interface TranslationProvider {

    /**
     * Translates English {@code text} into {@code targetLanguage} (a {@link Language} code).
     * Line breaks in the input must be kept as line breaks in the output.
     *
     * @throws IOException when the service can't be reached or refuses the request
     */
    String translate(String text, String targetLanguage) throws IOException;
}
