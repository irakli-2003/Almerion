package com.dnd.ui.i18n;

import java.io.IOException;
import java.util.List;

/** Tries each provider in turn and returns the first successful translation. */
public final class FallbackProvider implements TranslationProvider {
    private final List<TranslationProvider> providers;

    public FallbackProvider(List<TranslationProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public String translate(String text, String targetLanguage) throws IOException {
        IOException last = null;
        for (TranslationProvider provider : providers) {
            try {
                return provider.translate(text, targetLanguage);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No translation provider configured");
    }
}
