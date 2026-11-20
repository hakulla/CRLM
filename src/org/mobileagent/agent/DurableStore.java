package org.mobileagent.agent;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Private same-directory durable replacement. No delete-before-rename or copy-to-target fallback.
 * Requires a platform/filesystem supporting atomic replacement AND directory fsync; otherwise throws.
 * Android uses public android.system.Os by reflection (API 21+); desktop uses NIO ATOMIC_MOVE.
 * A post-rename failure is an uncertain commit: caller must BLOCK and recover, not repeat blindly.
 */
public final class DurableStore {
    public enum Stage { PREFLIGHT, TEMP_CREATED, TEMP_WRITTEN, TEMP_SYNCED, ORIGINAL_SAVED,
        BACKUP_SAVED, BEFORE_RENAME, AFTER_RENAME, DIRECTORY_SYNCED }
    public interface FaultInjector { void at(Stage stage, File target, File temp) throws IOException; }
    public interface Platform {
        void atomicReplace(File source, File target) throws IOException;
        void syncDirectory(File directory) throws IOException;
    }
    private static final FaultInjector NO_FAULT = new FaultInjector() {
        public void at(Stage stage, File target, File temp) { }
    };
    private DurableStore() { }
    public static void write(File target, byte[] data) throws IOException {
        write(target, data, platform(), NO_FAULT);
    }
    public static void write(File target, byte[] data, FaultInjector faults) throws IOException {
        write(target, data, platform(), faults);
    }
    public static synchronized void write(File target, byte[] data, Platform platform, FaultInjector faults) throws IOException {
        if (target == null || data == null || platform == null || faults == null) throw new IOException("Missing durable write input");
        File absolute = target.getAbsoluteFile();
        File directory = absolute.getParentFile();
        if (directory == null || !directory.isDirectory()) throw new IOException("Missing private directory: " + directory);
        directory = directory.getCanonicalFile();
        target = new File(directory, absolute.getName());
        regularOrMissing(target);
        File original = originalFor(target), backup = backupFor(target);
        regularOrMissing(original); regularOrMissing(backup);
        // Contents fsync is the durability enhancement: the content of the document itself will fsync + Atom rename. 
        // Directory not supported by individual filesystems fsync The whole saving should not be allowed to fail.
        syncDirectoryQuietly(platform, directory);
        faults.at(Stage.PREFLIGHT, target, null);
        File temp = privateTemp(directory, target.getName());
        faults.at(Stage.TEMP_CREATED, target, temp);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            faults.at(Stage.TEMP_WRITTEN, target, temp);
            out.flush(); out.getFD().sync();
            faults.at(Stage.TEMP_SYNCED, target, temp);
        }
        if (target.exists()) {
            if (!original.exists()) {
                saveCopy(target, original, platform);
                faults.at(Stage.ORIGINAL_SAVED, target, temp);
            }
            // The old version is changed to the original name .bak(Atom rename, No longer copy the entire document at a time;
            // If you crash, the main file is missing + .bak The presence of a presence would follow the established visible recovery process.
            platform.atomicReplace(target, backup);
            faults.at(Stage.BACKUP_SAVED, target, temp);
        }
        faults.at(Stage.BEFORE_RENAME, target, temp);
        platform.atomicReplace(temp, target);
        faults.at(Stage.AFTER_RENAME, target, temp);
        syncDirectoryQuietly(platform, directory);
        faults.at(Stage.DIRECTORY_SYNCED, target, temp);
    }
    public static File backupFor(File target) { return new File(target.getParentFile(), target.getName() + ".bak"); }
    public static File originalFor(File target) { return new File(target.getParentFile(), target.getName() + ".original"); }
    private static void regularOrMissing(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile()) || file.exists() && !file.isFile())
            throw new IOException("Not a regular private file: " + file);
    }
    private static File privateTemp(File parent, String name) throws IOException {
        File temp = File.createTempFile("." + name + "-", ".pending", parent);
        if (!temp.setReadable(false, false) || !temp.setWritable(false, false)
                || !temp.setExecutable(false, false) || !temp.setReadable(true, true) || !temp.setWritable(true, true))
            throw new IOException("Cannot enforce private temporary file permissions: " + temp);
        return temp;
    }
    private static void saveCopy(File source, File destination, Platform platform) throws IOException {
        File temp = privateTemp(source.getParentFile(), destination.getName());
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush(); output.getFD().sync();
        }
        platform.atomicReplace(temp, destination);
        syncDirectoryQuietly(platform, source.getParentFile());
    }
    private static void syncDirectoryQuietly(Platform platform, File directory) {
        try { platform.syncDirectory(directory); } catch (IOException ignored) { }
    }
    public static Platform platform() throws IOException {
        try { return new AndroidPosix(Class.forName("android.system.Os")); }
        catch (ClassNotFoundException e) { return new DesktopNio(); }
        catch (ReflectiveOperationException e) { throw new IOException("Android POSIX durability unavailable", e); }
    }
    private static final class DesktopNio implements Platform {
        public void atomicReplace(File source, File target) throws IOException {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        public void syncDirectory(File directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { channel.force(true); }
        }
    }
    /** Kept independent of Android imports so this production code is exercised on a plain JVM. */
    private static final class AndroidPosix implements Platform {
        private final Class<?> os;
        private final int flags;
        AndroidPosix(Class<?> os) throws ReflectiveOperationException {
            this.os = os;
            // Only by O_RDONLY Open directory:Linux Allow read-only directories open + fsync; 
            // O_DIRECTORY In part Android Version/The file system will let open Direct EINVAL. 
            int readOnly = 0;
            try {
                Class<?> constants = Class.forName("android.system.OsConstants");
                readOnly = constants.getField("O_RDONLY").getInt(null);
            } catch (Exception ignored) {
                // O_RDONLY is 0 on Android/Linux.
            }
            flags = readOnly;
        }
        private Object call(String name, Class<?>[] types, Object... args) throws IOException {
            try { return os.getMethod(name, types).invoke(null, args); }
            catch (InvocationTargetException e) { throw new IOException("POSIX " + name + " failed", e.getCause()); }
            catch (ReflectiveOperationException e) { throw new IOException("POSIX " + name + " unavailable", e); }
        }
        public void atomicReplace(File source, File target) throws IOException {
            call("rename", new Class<?>[] { String.class, String.class }, source.getPath(), target.getPath());
        }
        public void syncDirectory(File directory) throws IOException {
            FileDescriptor fd = (FileDescriptor) call("open", new Class<?>[] { String.class, int.class, int.class }, directory.getPath(), flags, 0);
            IOException error = null;
            try { call("fsync", new Class<?>[] { FileDescriptor.class }, fd); }
            catch (IOException e) { error = e; }
            try { call("close", new Class<?>[] { FileDescriptor.class }, fd); }
            catch (IOException e) { if (error == null) error = e; else error.addSuppressed(e); }
            if (error != null) throw error;
        }
    }
}
