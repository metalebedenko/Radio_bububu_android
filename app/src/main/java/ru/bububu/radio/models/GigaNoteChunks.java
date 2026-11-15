package ru.bububu.radio.models;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One saved-note recognition, with sequential bounded streams and one final publication. */
final class GigaNoteChunks {
    static final int MAX_SAMPLES = 1_920_000, STREAM_SAMPLES = 480_000, MAX_TEXT_BYTES = 65_536;
    interface Check { void run() throws Exception; }
    interface Factory { Session open() throws Exception; }
    interface Session {
        Stream openStream() throws Exception;
        void close() throws Exception;
    }
    interface Stream {
        void accept(float[] samples) throws Exception;
        void decode() throws Exception;
        String text() throws Exception;
        void close() throws Exception;
    }
    static final class CleanupFailure extends IOException {
        CleanupFailure(Throwable cause) { super("GigaAM: освобождение модели не подтверждено; перезапустите приложение", cause); }
    }

    static int[] boundaries(int samples) throws IOException {
        if (samples < 1 || samples > MAX_SAMPLES) throw new IOException("Нужна запись от одного отсчёта до 120 секунд; оригинал сохранён");
        int count = (samples + STREAM_SAMPLES - 1) / STREAM_SAMPLES;
        int q = samples / count, r = samples % count;
        int[] boundaries = new int[count + 1];
        for (int i = 0; i <= count; i++) boundaries[i] = i * q + Math.min(i, r);
        return boundaries;
    }

    static String run(float[] samples, Factory factory, Check check, Check verifyPack) throws IOException {
        int[] cuts = boundaries(samples == null ? 0 : samples.length);
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        Session session = null;
        try {
            try {
                check.run();
                session = factory.open();
                if (session == null) throw new IOException("GigaAM: распознаватель недоступен");
                check.run();
                for (int i = 0; i + 1 < cuts.length; i++) {
                    check.run();
                    Stream stream = null;
                    String text;
                    try {
                        stream = session.openStream();
                        if (stream == null) throw new IOException("GigaAM: поток недоступен");
                        check.run();
                        stream.accept(Arrays.copyOfRange(samples, cuts[i], cuts[i + 1]));
                        check.run();
                        stream.decode();
                        check.run();
                        text = stream.text();
                        check.run();
                        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
                            throw new IOException("GigaAM: полный результат не получен");
                    } finally {
                        if (stream != null) try { stream.close(); }
                        catch (Throwable failure) { throw new CleanupFailure(failure); }
                    }
                    check.run();
                    verifyPack.run();
                    check.run();
                    if (!text.isEmpty()) {
                        int separator = result.length() == 0 ? 0 : 1;
                        int next = bytes + separator + text.getBytes(StandardCharsets.UTF_8).length;
                        if (next > MAX_TEXT_BYTES) throw new IOException("GigaAM: полный результат слишком длинный; оригинал сохранён");
                        if (separator != 0) result.append('\n');
                        result.append(text);
                        bytes = next;
                    }
                }
            } finally {
                if (session != null) try { session.close(); }
                catch (Throwable failure) { throw new CleanupFailure(failure); }
            }
            check.run();
            verifyPack.run();
            check.run();
            if (result.toString().trim().isEmpty()) throw new IOException("Речь не распознана. Оригинал сохранён");
            return result.toString();
        } catch (IOException failure) { throw failure; }
        catch (Throwable failure) { throw new IOException("GigaAM: полная расшифровка не получена; оригинал сохранён", failure); }
    }
    private GigaNoteChunks() { }
}
