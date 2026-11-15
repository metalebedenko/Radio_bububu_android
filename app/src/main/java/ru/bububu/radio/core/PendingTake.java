package ru.bububu.radio.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** One live, never-published take; saved originals are never deletion candidates. */
public final class PendingTake {
    private PendingTake() {}
    public static Object identity(File part) throws IOException {
        BasicFileAttributes a=Files.readAttributes(part.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.isSymbolicLink()||a.fileKey()==null)throw new IOException("Pending take identity is unknown");
        return a.fileKey();
    }
    public static void discard(File part,File original,Object identity,boolean stopped,boolean certain) throws IOException {
        if(!stopped||!certain||identity==null)throw new IOException("Stop/storage is not confirmed");
        if(!part.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.wav\\.part")
                ||!original.getName().equals(part.getName().substring(0,40))
                ||Files.isSymbolicLink(part.toPath())||!original.getCanonicalFile().getParentFile().equals(part.getCanonicalFile().getParentFile())
                ||Files.exists(original.toPath(),LinkOption.NOFOLLOW_LINKS)||!identity.equals(identity(part)))
            throw new IOException("Take changed or original already committed; retained");
        Files.delete(part.toPath());
    }
}
