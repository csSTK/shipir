package dev.bethge.shipir.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal streaming tar (ustar) writer. File sizes must be known up front; names longer than ustar allows are
 * written with a PAX {@code path} record. Not thread-safe, does not close the underlying stream.
 */
final class TarWriter {

    private static final int BLOCK = 512;

    private final OutputStream out;
    private final byte[] buffer = new byte[64 * 1024];

    TarWriter(OutputStream out) {
        this.out = out;
    }

    void directory(String name, long mtimeSeconds) throws IOException {
        header(name.endsWith("/") ? name : name + "/", 0, mtimeSeconds, '5', 0755);
    }

    /** Writes exactly {@code size} bytes from {@code data}; fails if the stream is shorter (file shrank). */
    void file(String name, long size, long mtimeSeconds, InputStream data) throws IOException {
        header(name, size, mtimeSeconds, '0', 0644);
        long remaining = size;
        while (remaining > 0) {
            int n = data.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new IOException("File changed while being archived: " + name);
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
        pad(size);
    }

    void finish() throws IOException {
        out.write(new byte[BLOCK * 2]);
    }

    private void header(String name, long size, long mtime, char type, int mode) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        String prefix = "";
        String shortName = name;
        boolean needsPax = nameBytes.length > 100 || !isAscii(name);
        if (needsPax && isAscii(name)) {
            int split = splitIndex(name);
            if (split > 0) {
                prefix = name.substring(0, split);
                shortName = name.substring(split + 1);
                needsPax = false;
            }
        }
        if (needsPax) {
            byte[] record = paxRecord("path", name);
            rawHeader("PaxHeader", "", record.length, mtime, 'x', 0644);
            out.write(record);
            pad(record.length);
            shortName = truncate(name, 99);
        }
        rawHeader(shortName, prefix, size, mtime, type, mode);
    }

    /** Index of a '/' that leaves a prefix of at most 155 and a name of at most 100 bytes, or -1. */
    private static int splitIndex(String name) {
        for (int i = name.length() - 1; i > 0; i--) {
            if (name.charAt(i) == '/' && i <= 155 && name.length() - i - 1 <= 100) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    private static String truncate(String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) {
            return s;
        }
        return new String(b, 0, max, StandardCharsets.UTF_8).replace("�", "_");
    }

    private static byte[] paxRecord(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int len = body.getBytes(StandardCharsets.UTF_8).length;
        int total = len + String.valueOf(len).length();
        if (String.valueOf(total).length() != String.valueOf(len).length()) {
            total++;
        }
        return (total + body).getBytes(StandardCharsets.UTF_8);
    }

    private void rawHeader(String name, String prefix, long size, long mtime, char type, int mode) throws IOException {
        byte[] h = new byte[BLOCK];
        put(h, 0, 100, name);
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, mtime);
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        put(h, 257, 6, "ustar");
        h[263] = '0';
        h[264] = '0';
        put(h, 345, 155, prefix);
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        String chk = String.format("%06o", sum);
        put(h, 148, 6, chk);
        h[154] = 0;
        h[155] = ' ';
        out.write(h);
    }

    private static void put(byte[] h, int off, int len, String value) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len));
    }

    private static void octal(byte[] h, int off, int len, long value) {
        String s = String.format("%0" + (len - 1) + "o", value);
        put(h, off, len - 1, s);
    }

    private void pad(long size) throws IOException {
        int rest = (int) (size % BLOCK);
        if (rest != 0) {
            out.write(new byte[BLOCK - rest]);
        }
    }
}
