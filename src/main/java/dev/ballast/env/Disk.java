package dev.ballast.env;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Storage within one already-bound directory. Names are flat portable file names, not paths.
 * Writes/namespace changes are volatile until their corresponding sync returns successfully.
 * File sync persists bytes and length; directory sync persists names but not file contents.
 * Implementations are used by one serialized owner; no implicit thread-safety is promised.
 */
public interface Disk extends AutoCloseable {
    /** Lists the current namespace.
     * @return file names in lexicographic order
     * @throws IOException on storage failure */
    List<String> list() throws IOException;
    /** Creates a new empty file; fails if it exists.
     * @param name file name
     * @throws IOException on storage failure */
    void create(String name) throws IOException;
    /** Reads the current file length.
     * @param name file name
     * @return current byte length
     * @throws IOException on storage failure */
    long size(String name) throws IOException;
    /** Reads at an offset and advances the buffer position; zero remaining space returns zero.
     * @param name file name
     * @param offset nonnegative byte offset
     * @param target destination buffer
     * @return bytes read, or -1 at EOF with a nonempty target
     * @throws IOException on storage failure */
    int read(String name, long offset, ByteBuffer target) throws IOException;
    /** Writes at an offset and advances the buffer position; short/zero progress is permitted.
     * @param name file name
     * @param offset nonnegative byte offset
     * @param source bytes to write
     * @return bytes written
     * @throws IOException on storage failure */
    int write(String name, long offset, ByteBuffer source) throws IOException;
    /** Shrinks a file; a length at least its current size has no effect.
     * @param name file name
     * @param size nonnegative desired length
     * @throws IOException on storage failure */
    void truncate(String name, long size) throws IOException;
    /** Persists this file's contents and length, but not its directory entry.
     * @param name file name
     * @throws IOException on storage failure */
    void syncFile(String name) throws IOException;
    /** Atomically renames/replaces a name in the volatile namespace; needs directory sync.
     * @param source existing source name
     * @param target destination name
     * @throws IOException on storage failure */
    void move(String source, String target) throws IOException;
    /** Removes an existing name; needs directory sync.
     * @param name existing file name
     * @throws IOException on storage failure */
    void delete(String name) throws IOException;
    /** Persists the current directory namespace, not its files' bytes.
     * @throws IOException on storage failure */
    void syncDirectory() throws IOException;
    /** Releases resources without promising an implicit sync.
     * @throws IOException on cleanup failure */
    @Override
    void close() throws IOException;
}
