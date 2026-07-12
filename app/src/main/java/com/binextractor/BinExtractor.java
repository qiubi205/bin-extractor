package com.binextractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts .bin archive files. Supports:
 * - Simple concatenated header + data blobs
 * - Tar-like archives (detected by ustar magic)
 * - Raw fallback (copy as-is)
 */
public class BinExtractor {

    public static class ExtractedFile {
        public final String name;
        public final long size;
        public final byte[] data;

        ExtractedFile(String name, long size, byte[] data) {
            this.name = name;
            this.size = size;
            this.data = data;
        }
    }

    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100 MB per file

    /**
     * Analyze and extract the .bin file.
     * Returns a list of extracted files.
     */
    public static List<ExtractedFile> extract(File binFile) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();

        byte[] magic = readMagic(binFile);
        if (magic == null) return result;

        // Try tar format
        if (isTarFormat(magic, binFile)) {
            return extractTar(binFile);
        }

        // Try custom header format: 4-byte name length + name + 4-byte data length + data
        try {
            List<ExtractedFile> customResult = extractCustomFormat(binFile);
            if (!customResult.isEmpty()) {
                return customResult;
            }
        } catch (Exception ignored) {
        }

        // Fallback: just copy the file as-is
        byte[] data = readAllBytes(binFile, MAX_FILE_SIZE);
        result.add(new ExtractedFile(binFile.getName(), data.length, data));
        return result;
    }

    private static byte[] readMagic(File file) throws IOException {
        if (file.length() < 4) return null;
        byte[] buf = new byte[Math.min(512, (int) file.length())];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(buf);
            if (read < 4) return null;
            if (read < buf.length) {
                byte[] trimmed = new byte[read];
                System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            }
            return buf;
        }
    }

    private static boolean isTarFormat(byte[] magic, File file) {
        // ustar magic at offset 257 in a tar header block (512 bytes)
        if (file.length() < 512) return false;
        // Check for "ustar" at offset 257
        if (magic.length > 262 &&
                magic[257] == 'u' && magic[258] == 's' &&
                magic[259] == 't' && magic[260] == 'a' &&
                magic[261] == 'r') {
            return true;
        }
        // Also check at the beginning of the file
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[512];
            raf.readFully(header);
            if (header.length > 262 &&
                    header[257] == 'u' && header[258] == 's' &&
                    header[259] == 't' && header[260] == 'a' &&
                    header[261] == 'r') {
                return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static List<ExtractedFile> extractTar(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            while (raf.getFilePointer() < raf.length()) {
                // Read 512-byte tar header
                byte[] header = new byte[512];
                if (raf.read(header) < 512) break;

                // Check for end-of-archive (all zero blocks)
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) { allZero = false; break; }
                }
                if (allZero) break;

                // Read name (max 100 bytes, null-terminated)
                StringBuilder nameSb = new StringBuilder();
                for (int i = 0; i < 100; i++) {
                    if (header[i] == 0) break;
                    nameSb.append((char) (header[i] & 0xFF));
                }
                String name = nameSb.toString().trim();
                if (name.isEmpty()) continue;

                // Read size (octal, bytes 124-135)
                String sizeStr = "";
                for (int i = 124; i < 136; i++) {
                    if (header[i] == 0 || header[i] == ' ') break;
                    sizeStr += (char) (header[i] & 0xFF);
                }
                long size = 0;
                try {
                    size = Long.parseLong(sizeStr.trim(), 8);
                } catch (NumberFormatException e) {
                    continue;
                }

                if (size > MAX_FILE_SIZE) {
                    throw new IOException("File too large in tar: " + name + " (" + size + " bytes)");
                }

                if (size > 0) {
                    byte[] data = new byte[(int) size];
                    int offset = 0;
                    while (offset < size) {
                        int read = raf.read(data, offset, (int) Math.min(512, size - offset));
                        if (read < 0) break;
                        offset += read;
                    }
                    result.add(new ExtractedFile(name, size, data));
                }

                // Skip padding to next 512-byte boundary
                long padding = (512 - (size % 512)) % 512;
                if (padding > 0) {
                    raf.skipBytes((int) padding);
                }
            }
        }
        return result;
    }

    private static List<ExtractedFile> extractCustomFormat(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            while (raf.getFilePointer() < raf.length()) {
                // Read name length (4 bytes, little-endian)
                byte[] lenBuf = new byte[4];
                if (raf.read(lenBuf) < 4) break;
                int nameLen = (lenBuf[0] & 0xFF) |
                        ((lenBuf[1] & 0xFF) << 8) |
                        ((lenBuf[2] & 0xFF) << 16) |
                        ((lenBuf[3] & 0xFF) << 24);

                if (nameLen <= 0 || nameLen > 1024) {
                    // Not our format, rewind and stop
                    raf.seek(0);
                    return result;
                }

                // Read name
                byte[] nameBytes = new byte[nameLen];
                if (raf.read(nameBytes) < nameLen) break;
                String name = new String(nameBytes, "UTF-8");

                // Read data length (4 bytes, little-endian)
                if (raf.read(lenBuf) < 4) break;
                int dataLen = (lenBuf[0] & 0xFF) |
                        ((lenBuf[1] & 0xFF) << 8) |
                        ((lenBuf[2] & 0xFF) << 16) |
                        ((lenBuf[3] & 0xFF) << 24);

                if (dataLen <= 0 || dataLen > MAX_FILE_SIZE) {
                    raf.seek(0);
                    return result;
                }

                // Read data
                byte[] data = new byte[dataLen];
                if (raf.read(data) < dataLen) break;

                result.add(new ExtractedFile(name, dataLen, data));
            }
        }
        if (result.isEmpty()) {
            // Not our format
            return result;
        }
        return result;
    }

    private static byte[] readAllBytes(File file, int maxSize) throws IOException {
        long len = file.length();
        if (len > maxSize) {
            throw new IOException("File too large: " + len + " bytes (max " + maxSize + ")");
        }
        byte[] data = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read < 0) throw new IOException("Unexpected EOF");
                offset += read;
            }
        }
        return data;
    }
}
