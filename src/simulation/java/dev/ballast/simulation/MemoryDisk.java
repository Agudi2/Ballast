package dev.ballast.simulation;

import dev.ballast.env.Disk;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Stage 1 disk skeleton. A crash discards all unsynced changes. Torn writes, partial persistence,
 * injected failures, and byte-level fault schedules arrive in Stage 3.
 */
public final class MemoryDisk implements Disk {
    private final Map<String, Inode> visible = new TreeMap<>();
    private final Map<String, Inode> durableNames = new TreeMap<>();
    private boolean closed;

    private static final class Inode {
        private byte[] bytes = new byte[0];
        private byte[] durableBytes = new byte[0];
    }

    @Override
    public List<String> list() { checkOpen(); return List.copyOf(visible.keySet()); }

    @Override
    public void create(String name) throws IOException {
        checkName(name);
        if (visible.putIfAbsent(name, new Inode()) != null) throw new FileAlreadyExistsException(name);
    }

    @Override
    public long size(String name) throws IOException { return file(name).bytes.length; }

    @Override
    public int read(String name, long offset, ByteBuffer target) throws IOException {
        Inode inode = file(name);
        checkOffset(offset);
        Objects.requireNonNull(target, "target");
        if (!target.hasRemaining()) return 0;
        if (offset >= inode.bytes.length) return -1;
        int count = Math.min(target.remaining(), inode.bytes.length - (int) offset);
        target.put(inode.bytes, (int) offset, count);
        return count;
    }

    @Override
    public int write(String name, long offset, ByteBuffer source) throws IOException {
        Inode inode = file(name);
        checkOffset(offset);
        Objects.requireNonNull(source, "source");
        int count = source.remaining();
        if (count == 0) return 0;
        if (offset > Integer.MAX_VALUE - (long) count) throw new IOException("memory disk file exceeds array limit");
        int end = (int) offset + count;
        if (end > inode.bytes.length) inode.bytes = Arrays.copyOf(inode.bytes, end);
        source.get(inode.bytes, (int) offset, count);
        return count;
    }

    @Override
    public void truncate(String name, long size) throws IOException {
        Inode inode = file(name);
        checkOffset(size);
        if (size < inode.bytes.length) inode.bytes = Arrays.copyOf(inode.bytes, (int) size);
    }

    @Override
    public void syncFile(String name) throws IOException {
        Inode inode = file(name);
        inode.durableBytes = inode.bytes.clone();
    }

    @Override
    public void move(String source, String target) throws IOException {
        Inode inode = file(source);
        checkName(target);
        if (source.equals(target)) return;
        visible.remove(source);
        visible.put(target, inode);
    }

    @Override
    public void delete(String name) throws IOException { file(name); visible.remove(name); }

    @Override
    public void syncDirectory() {
        checkOpen();
        durableNames.clear();
        durableNames.putAll(visible);
    }

    /** Discards volatile state and reopens the simulated directory after process loss. */
    public void crash() {
        visible.clear();
        visible.putAll(durableNames);
        visible.values().forEach(inode -> inode.bytes = inode.durableBytes.clone());
        closed = false;
    }

    @Override
    public void close() { closed = true; }

    private Inode file(String name) throws IOException {
        checkName(name);
        Inode inode = visible.get(name);
        if (inode == null) throw new NoSuchFileException(name);
        return inode;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("disk is closed");
    }

    private void checkName(String name) {
        checkOpen();
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("expected a flat portable file name");
        }
    }

    private static void checkOffset(long offset) {
        if (offset < 0) throw new IllegalArgumentException("negative file offset or size");
    }
}
