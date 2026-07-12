package com.binextractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class BinExtractor {

    private static final String RPGM_MAGIC = "RPGM";
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100 MB

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

    public static List<ExtractedFile> extract(File binFile) throws IOException {
        byte[] header = readBytes(binFile, 0, 16);
        if (header == null || header.length < 4) {
            return fallbackCopy(binFile);
        }

        String magic = new String(header, 0, 4, "ASCII");
        if (magic.equals(RPGM_MAGIC)) {
            return extractRPGM(binFile, header);
        }

        // Try tar
        if (isTar(binFile)) {
            return extractTar(binFile);
        }

        // Try custom format
        List<ExtractedFile> customResult = extractCustom(binFile);
        if (!customResult.isEmpty()) return customResult;

        return fallbackCopy(binFile);
    }

    // ========== RPGM 格式解包 ==========
    // 魔数 "RPGM" (4字节)
    // 4字节: 未知（版本？）
    // 4字节: 图片类型 (03=PNG?)
    // 之后跟着实际图片数据
    private static List<ExtractedFile> extractRPGM(File file, byte[] header) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();

        // 跳过 RPGM 头部 - 看起来是 16 字节
        // 魔数4 + 未知4 + 类型4 + 额外4
        int headerSize = 16;

        // 检查第二个块
        long fileLen = file.length();
        if (fileLen <= headerSize) {
            return fallbackCopy(file);
        }

        // 从 headerSize 开始读取后续数据块
        int offset = headerSize;
        int index = 0;

        while (offset < fileLen) {
            // 每块开头可能有额外的数据块头
            // 读4字节块长度
            if (offset + 4 > fileLen) break;
            byte[] lenBytes = readBytes(file, offset, 4);
            if (lenBytes == null) break;

            int chunkLen = ((lenBytes[0] & 0xFF) |
                    ((lenBytes[1] & 0xFF) << 8) |
                    ((lenBytes[2] & 0xFF) << 16) |
                    ((lenBytes[3] & 0xFF) << 24));

            if (chunkLen <= 0 || chunkLen > fileLen - offset - 4) {
                // 不是块长度头，尝试把剩余全部读成一个块
                byte[] rawData = readBytes(file, offset, (int)(fileLen - offset));
                if (rawData != null && rawData.length > 0) {
                    String ext = detectImageExt(rawData);
                    result.add(new ExtractedFile("image_" + index + ext, rawData.length, rawData));
                    index++;
                }
                break;
            }

            offset += 4;
            if (offset + chunkLen > fileLen) {
                chunkLen = (int)(fileLen - offset);
            }

            byte[] chunkData = readBytes(file, offset, chunkLen);
            if (chunkData == null) break;

            // 检测图片类型
            String ext = detectImageExt(chunkData);
            result.add(new ExtractedFile("image_" + index + ext, chunkData.length, chunkData));
            index++;
            offset += chunkLen;
        }

        if (result.isEmpty()) {
            // 尝试直接跳过 16 字节头读剩余全部
            byte[] remaining = readBytes(file, headerSize, (int)(fileLen - headerSize));
            if (remaining != null && remaining.length > 0) {
                String ext = detectImageExt(remaining);
                result.add(new ExtractedFile("extracted" + ext, remaining.length, remaining));
            }
        }

        // 如果结果里的文件不是标准图片格式，加个 .bin 后缀保底
        for (ExtractedFile f : result) {
            if (!f.name.contains(".")) {
                // 保持原名，不自动加后缀
            }
        }

        return result;
    }

    private static String detectImageExt(byte[] data) {
        if (data.length < 4) return ".bin";
        // PNG
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return ".png";
        // JPEG
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return ".jpg";
        // GIF
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return ".gif";
        // BMP
        if (data[0] == 'B' && data[1] == 'M') return ".bmp";
        // WEBP
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return ".webp";
        return ".bin";
    }

    // ========== Tar 解包 ==========
    private static boolean isTar(File file) throws IOException {
        if (file.length() < 512) return false;
        byte[] buf = readBytes(file, 257, 5);
        if (buf == null) return false;
        String ustar = new String(buf, "ASCII");
        return ustar.equals("ustar");
    }

    private static List<ExtractedFile> extractTar(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            while (raf.getFilePointer() < raf.length()) {
                byte[] header = new byte[512];
                if (raf.read(header) < 512) break;
                boolean allZero = true;
                for (byte b : header) { if (b != 0) { allZero = false; break; } }
                if (allZero) break;

                StringBuilder nameSb = new StringBuilder();
                for (int i = 0; i < 100; i++) {
                    if (header[i] == 0) break;
                    nameSb.append((char)(header[i] & 0xFF));
                }
                String name = nameSb.toString().trim();
                if (name.isEmpty()) continue;

                String sizeStr = "";
                for (int i = 124; i < 136; i++) {
                    if (header[i] == 0 || header[i] == ' ') break;
                    sizeStr += (char)(header[i] & 0xFF);
                }
                long size = 0;
                try { size = Long.parseLong(sizeStr.trim(), 8); } catch (NumberFormatException e) { continue; }
                if (size > MAX_FILE_SIZE) throw new IOException("Tar 内文件过大: " + name);

                if (size > 0) {
                    byte[] data = new byte[(int) size];
                    int off = 0;
                    while (off < size) {
                        int read = raf.read(data, off, (int) Math.min(512, size - off));
                        if (read < 0) break;
                        off += read;
                    }
                    result.add(new ExtractedFile(name, size, data));
                }
                long padding = (512 - (size % 512)) % 512;
                if (padding > 0) raf.skipBytes((int) padding);
            }
        }
        return result;
    }

    // ========== 自定义格式 ==========
    private static List<ExtractedFile> extractCustom(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            while (raf.getFilePointer() < raf.length()) {
                byte[] lenBuf = new byte[4];
                if (raf.read(lenBuf) < 4) break;
                int nameLen = (lenBuf[0] & 0xFF) | ((lenBuf[1] & 0xFF) << 8) |
                        ((lenBuf[2] & 0xFF) << 16) | ((lenBuf[3] & 0xFF) << 24);
                if (nameLen <= 0 || nameLen > 1024) { raf.seek(0); return result; }

                byte[] nameBytes = new byte[nameLen];
                if (raf.read(nameBytes) < nameLen) break;
                String name = new String(nameBytes, "UTF-8");

                if (raf.read(lenBuf) < 4) break;
                int dataLen = (lenBuf[0] & 0xFF) | ((lenBuf[1] & 0xFF) << 8) |
                        ((lenBuf[2] & 0xFF) << 16) | ((lenBuf[3] & 0xFF) << 24);
                if (dataLen <= 0 || dataLen > MAX_FILE_SIZE) { raf.seek(0); return result; }

                byte[] data = new byte[dataLen];
                if (raf.read(data) < dataLen) break;
                result.add(new ExtractedFile(name, dataLen, data));
            }
        }
        return result;
    }

    // ========== 兜底 ==========
    private static List<ExtractedFile> fallbackCopy(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        byte[] data = readAll(file, MAX_FILE_SIZE);
        String ext = detectImageExt(data);
        String name = file.getName();
        // 去掉可能不正确的后缀
        if (name.toLowerCase().endsWith(".bin") || name.toLowerCase().endsWith(".bin_")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        // 加正确后缀
        if (!name.endsWith(ext)) {
            name += ext;
        }
        result.add(new ExtractedFile(name, data.length, data));
        return result;
    }

    // ========== 工具函数 ==========
    private static byte[] readBytes(File file, long offset, int len) throws IOException {
        if (offset + len > file.length()) {
            len = (int)(file.length() - offset);
            if (len <= 0) return null;
        }
        byte[] buf = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            int read = raf.read(buf);
            if (read < len) {
                byte[] trimmed = new byte[read];
                System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            }
        }
        return buf;
    }

    private static byte[] readAll(File file, int maxSize) throws IOException {
        long len = file.length();
        if (len > maxSize) throw new IOException("文件过大: " + len);
        byte[] data = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int read = fis.read(data, off, data.length - off);
                if (read < 0) throw new IOException("意外 EOF");
                off += read;
            }
        }
        return data;
    }
}
