package ru.bububu.radio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A fixed, ordered local station queue for the first test build. */
public final class Station {
    public final String id;
    public final String name;
    public final List<Article> articles;

    public Station(String id, String name, List<Article> articles) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        if (id.trim().isEmpty() || name.trim().isEmpty()) throw new IllegalArgumentException("Empty station");
        Set<String> ids = new HashSet<>();
        ArrayList<Article> copy = new ArrayList<>(articles);
        for (Article article : copy) {
            Objects.requireNonNull(article, "article");
            if (!ids.add(article.id)) throw new IllegalArgumentException("Duplicate article ID in station");
        }
        this.articles = Collections.unmodifiableList(copy);
    }
}
