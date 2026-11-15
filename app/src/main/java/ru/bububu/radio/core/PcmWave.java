package ru.bububu.radio.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** The bounded, recoverable PCM format used for note originals. No Android dependency. */
public final class PcmWave {
    public static final int SAMPLE_RATE = 16_000;
    public static final int HEADER_BYTES = 44;
    public static final long MAX_DURATION_MS = 120_000;
    public static final long MAX_PCM_BYTES = SAMPLE_RATE * 2L * MAX_DURATION_MS / 1000;
    public static final long LOCAL_TRANSCRIPTION_MS = MAX_DURATION_MS;
    public static final int LOCAL_TRANSCRIPTION_BYTES = HEADER_BYTES + (int) MAX_PCM_BYTES;

    private PcmWave() { }

    /** AudioRecord short[] values are encoded explicitly, independent of native endianness. */
    public static int encodePcm16(short[] samples, int count, byte[] output) {
        if (count < 0 || count > samples.length || count > output.length / 2) {
            throw new IllegalArgumentException("Invalid PCM16 sample count");
        }
        for (int n = 0; n < count; n++) {
            output[n * 2] = (byte) (samples[n] & 255);
            output[n * 2 + 1] = (byte) ((samples[n] >>> 8) & 255);
        }
        return count * 2;
    }

    public static final class Info {
        public final long pcmBytes;
        public final long durationMs;
        Info(long pcmBytes) {
            this.pcmBytes = pcmBytes;
            durationMs = pcmBytes * 1000 / (SAMPLE_RATE * 2L);
        }
    }

    public static final class Writer implements AutoCloseable {
        private final File part;
        private RandomAccessFile output;

        public Writer(File part) throws IOException {
            this.part = part;
            requireDirectory(part);
            if (!part.createNewFile()) throw new IOException("Original already exists");
            try {
                output = new RandomAccessFile(part, "rw");
                header(output, 0);
                output.getFD().sync();
            } catch (IOException error) {
                close();
                throw error;
            }
        }

        public void append(byte[] data, int offset, int length) throws IOException {
            if (output == null) throw new IOException("Writer is closed");
            if (offset < 0 || length < 0 || offset > data.length - length || length % 2 != 0) {
                throw new IllegalArgumentException("Whole PCM16 samples required");
            }
            if (output.length() - HEADER_BYTES + length > MAX_PCM_BYTES) {
                throw new IOException("Recording limit reached");
            }
            output.seek(output.length());
            output.write(data, offset, length);
        }

        public long pcmBytes() throws IOException {
            if (output == null) throw new IOException("Writer is closed");
            return output.length() - HEADER_BYTES;
        }

        public void sync() throws IOException {
            if (output == null) throw new IOException("Writer is closed");
            output.getFD().sync();
        }

        public Info finish(File wave) throws IOException {
            close();
            return recover(part, wave);
        }

        @Override public void close() throws IOException {
            RandomAccessFile file = output;
            output = null;
            if (file != null) {
                try { file.getFD().sync(); }
                finally { file.close(); }
            }
        }
    }

    /** Salvage complete, actually written samples; never rely on an unfinished length header. */
    public static Info recover(File part, File wave) throws IOException {
        regularAttributes(part);
        requireDirectory(wave);
        if (!Files.notExists(wave.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing to replace an original");
        long bytes;
        try (FileChannel channel = FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            bytes = channel.size() - HEADER_BYTES;
            if (bytes < 0 || bytes > MAX_PCM_BYTES) throw new IOException("Invalid PCM original size");
            bytes -= bytes % 2;
            channel.truncate(HEADER_BYTES + bytes);
            header(new DataOutputStream(Channels.newOutputStream(channel)), bytes);
            channel.force(true);
        }
        Files.move(part.toPath(), wave.toPath()); // Do not replace a committed original or a dangling link.
        return new Info(bytes);
    }

    public static long readDuration(File wave) throws IOException { return readInfo(wave).durationMs; }

    public static Info readInfo(File wave) throws IOException {
        regularAttributes(wave);
        try (FileChannel channel = FileChannel.open(wave.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return readInfo(new DataInputStream(Channels.newInputStream(channel)), channel.size());
        }
    }

    static BasicFileAttributes regularAttributes(File file) throws IOException {
        requireDirectory(file);
        BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Expected a regular note file");
        return attributes;
    }

    private static void requireDirectory(File file) throws IOException {
        if (!Files.isDirectory(file.getAbsoluteFile().getParentFile().toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe notes directory");
    }

    /** Validate the same open descriptor that will supply raw bytes; its position ends after the header. */
    public static Info readInfo(RandomAccessFile file) throws IOException {
        file.seek(0);
        return readInfo(file, file.length());
    }

    /** Read-only input from the app's approved originals directory; never truncate an original. */
    public static byte[] readLocalOriginal(File directory, String id, BooleanSupplier cancelled) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IOException("Некорректная запись заметки"); }
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Оригинальная запись недоступна");
        File root = directory.getCanonicalFile(), wave = new File(root, id + ".wav");
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !wave.equals(wave.getCanonicalFile())
                || !Files.isRegularFile(wave.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Оригинальная запись недоступна");
        byte[] bytes;
        try (SeekableByteChannel input = Files.newByteChannel(wave.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input.size() > LOCAL_TRANSCRIPTION_BYTES) throw localLimit();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (true) {
                if (cancelled.getAsBoolean()) throw new IOException("Расшифровка отменена; оригинал сохранён");
                int count = input.read(buffer);
                if (count < 0) break;
                if (output.size() + count > LOCAL_TRANSCRIPTION_BYTES) throw localLimit();
                output.write(buffer.array(), 0, count); buffer.clear();
            }
            bytes = output.toByteArray();
            if (input.size() != bytes.length) throw new IOException("Оригинальная запись изменилась");
        }
        Info info;
        try { info = readInfo(new DataInputStream(new ByteArrayInputStream(bytes)), bytes.length); }
        catch (IOException invalid) { throw new IOException("Формат оригинальной записи не подтверждён; оригинал сохранён", invalid); }
        if (info.pcmBytes <= 0) throw new IOException("В оригинальной записи нет звука");
        if (info.durationMs > LOCAL_TRANSCRIPTION_MS) throw localLimit();
        if (cancelled.getAsBoolean() || !wave.equals(wave.getCanonicalFile())
                || !Files.isRegularFile(wave.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Расшифровка отменена или оригинал недоступен; запись сохранена");
        return bytes;
    }
    private static IOException localLimit() {
        return new IOException("GigaAM принимает заметки до 120 секунд. Полный оригинал сохранён; доступен ручной текст.");
    }
    private static Info readInfo(DataInput file, long length) throws IOException {
        long bytes = length - HEADER_BYTES;
        if (bytes < 0 || bytes > MAX_PCM_BYTES || bytes % 2 != 0) throw new IOException("Invalid PCM size");
        if (file.readInt() != 0x52494646 || unsignedInt(file) != bytes + 36
                || file.readInt() != 0x57415645 || file.readInt() != 0x666d7420
                || unsignedInt(file) != 16 || unsignedShort(file) != 1 || unsignedShort(file) != 1
                || unsignedInt(file) != SAMPLE_RATE || unsignedInt(file) != SAMPLE_RATE * 2L
                || unsignedShort(file) != 2 || unsignedShort(file) != 16
                || file.readInt() != 0x64617461 || unsignedInt(file) != bytes) {
            throw new IOException("Unsupported or damaged original format");
        }
        return new Info(bytes);
    }

    private static void header(DataOutput file, long bytes) throws IOException {
        file.writeInt(0x52494646); littleInt(file, bytes + 36); file.writeInt(0x57415645);
        file.writeInt(0x666d7420); littleInt(file, 16); littleShort(file, 1); littleShort(file, 1);
        littleInt(file, SAMPLE_RATE); littleInt(file, SAMPLE_RATE * 2L); littleShort(file, 2);
        littleShort(file, 16); file.writeInt(0x64617461); littleInt(file, bytes);
    }

    private static void littleInt(DataOutput file, long value) throws IOException {
        for (int n = 0; n < 4; n++) file.write((int) (value >>> (8 * n)) & 255);
    }
    private static void littleShort(DataOutput file, int value) throws IOException {
        file.write(value & 255); file.write((value >>> 8) & 255);
    }
    private static long unsignedInt(DataInput file) throws IOException {
        long value = 0;
        for (int n = 0; n < 4; n++) value |= (long) file.readUnsignedByte() << (8 * n);
        return value;
    }
    private static int unsignedShort(DataInput file) throws IOException {
        return file.readUnsignedByte() | (file.readUnsignedByte() << 8);
    }
}
