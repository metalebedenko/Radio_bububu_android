package ru.bububu.radio.core;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Owner-thread authority over verified radio JSON, independent of the live player snapshot. */
public final class RadioStateAuthority implements PlayerController.StateStore {
    private final PlayerController.StateStore storage;
    private final UnaryOperator<PlayerController.SavedState> restore, encode;
    private final Thread owner = Thread.currentThread();
    private PlayerController.SavedState persisted;
    private boolean known;
    private long epoch;

    public RadioStateAuthority(PlayerController.StateStore storage,
            UnaryOperator<PlayerController.SavedState> restore,
            UnaryOperator<PlayerController.SavedState> encode) {
        this.storage = Objects.requireNonNull(storage);
        this.restore = Objects.requireNonNull(restore);
        this.encode = Objects.requireNonNull(encode);
    }
    @Override public PlayerController.SavedState load() {
        checkOwner();
        try {
            PlayerController.SavedState raw = storage.load();
            PlayerController.SavedState restored = restore.apply(raw);
            // Reconciliation can inject queue seeds. They are not persisted evidence.
            accept(raw);
            return restored;
        } catch (RuntimeException failure) { revoke(); throw failure; }
    }
    @Override public void save(PlayerController.SavedState state) {
        checkOwner();
        try {
            PlayerController.SavedState encoded = encode.apply(state);
            storage.save(encoded); // LocalData returns only after verified readback.
            accept(encoded);
        } catch (RuntimeException failure) {
            // Even an exception may follow publication. Never fall back to the old success.
            revoke();
            throw failure;
        }
    }
    public void requireKnown() {
        checkOwner();
        if (!known) throw new IllegalStateException("Сохранение прогресса не подтверждено; тексты сохранены");
    }
    public PlayerController.SavedState persisted() { requireKnown(); return persisted; }
    public boolean known() { checkOwner(); return known; }
    public long epoch() { checkOwner(); return epoch; }
    private void accept(PlayerController.SavedState state) {
        if (!known) epoch++;
        persisted = state;
        known = true;
    }
    private void revoke() { persisted = null; known = false; epoch++; }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Radio authority belongs to its owner thread");
    }
}
