package ru.bububu.radio.models;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Sequential setup on the existing model worker; ready metadata alone never proves reuse. */
final class BundledPacks {
    interface Source { InputStream open(PackCatalog pack) throws IOException; }
    private final ModelPackStore store;
    private final PackCatalog[] packs;
    private final Source source;
    private final LongSupplier freeBytes;

    BundledPacks(ModelPackStore store, PackCatalog[] packs, Source source, LongSupplier freeBytes) {
        this.store = store; this.packs = packs.clone(); this.source = source; this.freeBytes = freeBytes;
    }

    void prepare(ModelPackStore.Token token, Consumer<String> progress) throws IOException {
        for (int i = 0; i < packs.length; i++) {
            check(token);
            PackCatalog pack = packs[i];
            File ready = store.ready(pack);
            String name = pack.kind.equals("silero") ? "Silero" : "GigaAM";
            progress.accept((ready == null ? "Подготовка " : "Проверка готового пакета ") + name + " · " + (i + 1) + "/" + packs.length);
            if (ready == null) {
                if (freeBytes.getAsLong() < requiredFree(pack))
                    throw new IOException("Недостаточно свободного места для подготовки " + name + ". Освободите место и повторите; готовые пакеты сохранены.");
                check(token);
                // Each atomic pack commit has its own token; the outer job remains cancellable.
                try (InputStream input = source.open(pack)) { store.importPack(pack, input, new ModelPackStore.Token(token)); }
                ready = store.ready(pack);
                if (ready == null) throw new IOException("Готовность " + name + " не подтверждена");
            }
            // Includes same-size corruption. A bad ready generation is preserved, never silently reused.
            store.verify(pack, ready, token);
            check(token);
        }
    }

    static long requiredFree(PackCatalog pack) { return Math.addExact(Math.multiplyExact(pack.bytes, 2), 16777216L); }
    private static void check(ModelPackStore.Token token) throws IOException {
        if (token.cancelled()) throw new IOException("Подготовка моделей отменена. Готовые пакеты сохранены.");
    }
}
