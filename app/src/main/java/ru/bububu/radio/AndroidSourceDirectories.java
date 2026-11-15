package ru.bububu.radio;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import ru.bububu.radio.sources.SourceFailure;

/** Prepares only the owned child of an already existing private no-backup parent. */
final class AndroidSourceDirectories {
    private AndroidSourceDirectories() {}

    static File prepareNetwork(File noBackup) throws SourceFailure {
        try {
            Path parent=noBackup.toPath();
            requireAccessibleDirectory(parent);
            Path network=parent.resolve("source-network");
            try { requireAccessibleDirectory(network); }
            catch(NoSuchFileException absent) {
                // Do not interpret permission/type/provider failures as absence or replace an object.
                requireAccessibleDirectory(parent);
                Files.createDirectory(network);
                requireAccessibleDirectory(network);
            }
            requireAccessibleDirectory(parent);
            return network.toFile();
        } catch(IOException|RuntimeException failure) {
            throw new SourceFailure("COOLDOWN_STORAGE",failure);
        }
    }

    private static void requireAccessibleDirectory(Path path) throws IOException {
        BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory() || !Files.isReadable(path) || !Files.isWritable(path) || !Files.isExecutable(path))
            throw new IOException("Source network directory type or access unavailable");
        // Reading the actual directory must succeed; access-query false/uncertain is never sufficient.
        try(DirectoryStream<Path> ignored=Files.newDirectoryStream(path)) {}
    }
}
