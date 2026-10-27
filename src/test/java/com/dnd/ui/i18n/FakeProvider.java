package com.dnd.ui.i18n;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Offline stand-in for a translation service: prefixes every line with the language code. */
class FakeProvider implements TranslationProvider {
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile boolean failing;
    volatile boolean mergeLines;
    volatile boolean dropPlaceholders;

    @Override
    public String translate(String text, String targetLanguage) throws IOException {
        requests.add(text);
        if (failing) throw new IOException("offline");
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append(mergeLines ? " " : "\n");
            String line = targetLanguage.toUpperCase() + ":" + lines[i];
            out.append(dropPlaceholders ? line.replaceAll("\\{\\d+}", "") : line);
        }
        return out.toString();
    }
}
