package ru.bububu.radio.models;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Private immutable-file verification only. It does not parse or execute model/pack bytes. */
final class FileGuards {
    private FileGuards() {}

    static final class RejectedFile extends IOException {
        RejectedFile(String code) { super("FILE_GUARD_" + code); }
    }

    static final class Root {
        private final File directory;
        private final StructStat identity;
        private Root(File directory, StructStat identity) {
            this.directory = directory;
            this.identity = identity;
        }
    }

    static final class Spec {
        final String relativePath;
        final long bytes;
        final String sha256;
        final long cap;
        Spec(String relativePath, long bytes, String sha256, long cap) {
            if (relativePath == null || relativePath.length() > 512
                    || !relativePath.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
                throw new IllegalArgumentException("Invalid artifact path");
            }
            String[] parts = relativePath.split("/", -1);
            if (parts.length > 16) throw new IllegalArgumentException("Artifact path too deep");
            for (String part : parts) {
                if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 128) {
                    throw new IllegalArgumentException("Invalid artifact path component");
                }
            }
            if (bytes < 0 || cap < 1 || bytes > cap || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid artifact bounds/hash");
            }
            this.relativePath = relativePath;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.cap = cap;
        }
    }

    static final class VerifiedFile {
        private final Root root;
        private final Spec spec;
        private final File path;
        private VerifiedFile(Root root, Spec spec, File path) {
            this.root = root;
            this.spec = spec;
            this.path = path;
        }
        long bytes() { return spec.bytes; }
        String sha256() { return spec.sha256; }
        String reverifiedPath() throws IOException {
            VerifiedFile again = verify(root, spec);
            if (!path.equals(again.path)) throw new RejectedFile("PATH_CHANGED");
            return path.getAbsolutePath();
        }
    }

    /** The caller supplies its platform-owned getFilesDir(), never a pack-controlled root. */
    static Root anchorOwnedFilesDirectory(File platformFilesDirectory) throws IOException {
        File requested = platformFilesDirectory.getAbsoluteFile();
        FileDescriptor descriptor = null;
        try {
            StructStat named = Os.lstat(requested.getAbsolutePath());
            requireOwnedRootCandidate(named);
            // O_DIRECTORY is not in the public SDK. fstat must confirm DIR before any mutation.
            descriptor = Os.open(requested.getAbsolutePath(), OsConstants.O_RDONLY
                    | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            StructStat opened = Os.fstat(descriptor);
            requireOwnedRootCandidate(opened);
            requireSameIdentity(named, opened);
            if (named.st_nlink != opened.st_nlink) throw new RejectedFile("ROOT_LINK_COUNT_CHANGED");
            // AOSP getFilesDir may start at0771. Normalize only this pinned own root, never children.
            Os.fchmod(descriptor, 0700);
            StructStat normalized = Os.fstat(descriptor);
            requireDirectory(normalized);
            if ((normalized.st_mode & 0777) != 0700 || normalized.st_nlink != opened.st_nlink
                    || normalized.st_dev != opened.st_dev || normalized.st_ino != opened.st_ino) {
                throw new RejectedFile("ROOT_NORMALIZATION");
            }
            StructStat finalNamed = Os.lstat(requested.getAbsolutePath());
            requireDirectory(finalNamed);
            requireSameIdentity(normalized, finalNamed);
            if (finalNamed.st_nlink != normalized.st_nlink) throw new RejectedFile("ROOT_LINK_COUNT_CHANGED");
            File canonical = requested.getCanonicalFile();
            StructStat canonicalState = Os.lstat(canonical.getAbsolutePath());
            requireDirectory(canonicalState);
            requireSameIdentity(normalized, canonicalState);
            return new Root(canonical, normalized);
        } catch (ErrnoException failure) {
            throw new RejectedFile("ROOT_STAT");
        } finally {
            if (descriptor != null && descriptor.valid()) {
                try { Os.close(descriptor); } catch (ErrnoException ignored) { }
            }
        }
    }

    static File canonicalDirectory(Root root) throws IOException {
        if(root==null)throw new RejectedFile("MISSING_ROOT");
        try{
            StructStat current=Os.lstat(root.directory.getAbsolutePath());requireDirectory(current);requireSameIdentity(root.identity,current);
            return root.directory;
        }catch(ErrnoException failure){throw new RejectedFile("ROOT_STAT");}
    }

    private static void requireOwnedRootCandidate(StructStat state) throws RejectedFile {
        if (!OsConstants.S_ISDIR(state.st_mode) || state.st_uid != Process.myUid()
                || state.st_nlink < 1) throw new RejectedFile("ROOT_TYPE_OWNER_LINKS");
    }

    /** Specs are supplied by a future reviewed contract; there is no manifest parser yet. */
    static VerifiedFile verify(Root root, Spec spec) throws IOException {
        return verify(root, spec, null);
    }

    static byte[] readBytes(Root root, Spec spec) throws IOException {
        if (spec == null || spec.bytes > 1048576L) throw new RejectedFile("SMALL_READ_CAP");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) spec.bytes);
        verify(root, spec, bytes);
        return bytes.toByteArray();
    }

    static byte[] readResources(Root root,Spec spec)throws IOException {
        if(!spec.relativePath.equals("resources.json")||spec.bytes!=9835920||!spec.sha256.equals(PackCatalog.SILERO.entry("resources.json").sha))throw new RejectedFile("EXACT_RESOURCES_ONLY");
        ByteArrayOutputStream data=new ByteArrayOutputStream((int)spec.bytes);verify(root,spec,data);return data.toByteArray();
    }
    private static VerifiedFile verify(Root root, Spec spec, ByteArrayOutputStream capture) throws IOException {
        if (root == null || spec == null) throw new RejectedFile("MISSING_SPEC");
        File target = new File(root.directory, spec.relativePath);
        FileDescriptor descriptor = null;
        try {
            List<File> directories = new ArrayList<>();
            List<StructStat> identities = new ArrayList<>();
            File parent = root.directory;
            StructStat currentRoot = Os.lstat(parent.getAbsolutePath());
            requireDirectory(currentRoot);
            requireSameIdentity(root.identity, currentRoot);
            directories.add(parent);
            identities.add(currentRoot);
            String[] parts = spec.relativePath.split("/");
            for (int index = 0; index < parts.length - 1; index++) {
                parent = new File(parent, parts[index]);
                StructStat state = Os.lstat(parent.getAbsolutePath());
                requireDirectory(state);
                directories.add(parent);
                identities.add(state);
            }
            if (!target.getCanonicalFile().equals(target.getAbsoluteFile())) {
                throw new RejectedFile("CANONICAL_PATH");
            }
            StructStat named = Os.lstat(target.getAbsolutePath());
            requireFile(named, spec);
            descriptor = Os.open(target.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            StructStat opened = Os.fstat(descriptor);
            requireFile(opened, spec);
            requireSameIdentity(named, opened);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(descriptor)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total = Math.addExact(total, count);
                    if (total > spec.bytes || total > spec.cap) throw new RejectedFile("READ_BOUND");
                    digest.update(buffer, 0, count);
                    if (capture != null) capture.write(buffer, 0, count);
                }
                if (total != spec.bytes) throw new RejectedFile("READ_LENGTH");
                StructStat after = Os.fstat(descriptor);
                requireFile(after, spec);
                requireSameIdentity(opened, after);
                if (opened.st_mtime != after.st_mtime || opened.st_ctime != after.st_ctime) {
                    throw new RejectedFile("CHANGED_DURING_READ");
                }
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) {
                actual.append(Character.forDigit((value >>> 4) & 15, 16));
                actual.append(Character.forDigit(value & 15, 16));
            }
            if (!spec.sha256.equals(actual.toString())) throw new RejectedFile("SHA256");
            StructStat finalNamed = Os.lstat(target.getAbsolutePath());
            requireFile(finalNamed, spec);
            requireSameIdentity(opened, finalNamed);
            for (int index = 0; index < directories.size(); index++) {
                StructStat state = Os.lstat(directories.get(index).getAbsolutePath());
                requireDirectory(state);
                requireSameIdentity(identities.get(index), state);
            }
            return new VerifiedFile(root, spec, target);
        } catch (ErrnoException failure) {
            throw new RejectedFile("OS_" + failure.errno);
        } catch (NoSuchAlgorithmException | ArithmeticException failure) {
            throw new RejectedFile("HASH_OR_LENGTH");
        } finally {
            if (descriptor != null && descriptor.valid()) {
                try { Os.close(descriptor); } catch (ErrnoException ignored) { }
            }
        }
    }

    private static void requireDirectory(StructStat state) throws RejectedFile {
        if (!OsConstants.S_ISDIR(state.st_mode) || state.st_uid != Process.myUid()
                || (state.st_mode & 0022) != 0) throw new RejectedFile("DIRECTORY_TYPE_OWNER_MODE");
    }
    private static void requireFile(StructStat state, Spec spec) throws RejectedFile {
        if (!OsConstants.S_ISREG(state.st_mode) || state.st_nlink != 1
                || state.st_uid != Process.myUid() || (state.st_mode & 0222) != 0
                || state.st_size != spec.bytes || state.st_size > spec.cap) {
            throw new RejectedFile("FILE_TYPE_OWNER_MODE_LINKS_SIZE");
        }
    }
    private static void requireSameIdentity(StructStat before, StructStat after) throws RejectedFile {
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino
                || before.st_mode != after.st_mode || before.st_uid != after.st_uid) {
            throw new RejectedFile("IDENTITY_CHANGED");
        }
    }
}
