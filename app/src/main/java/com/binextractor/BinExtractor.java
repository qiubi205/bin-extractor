package com.binextractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class BinExtractor {

    private static final String RPGM_MAGIC = "RPGM";
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024;

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
        byte[] header = readBytes(binFile, 0, 4);
        if (header == null || header.length < 4) {
            return fallbackCopy(binFile);
        }

        String magic = new String(header, "ASCII");
        if (magic.equals(RPGM_MAGIC)) {
            return extractRPGM(binFile);
        }

        if (isTar(binFile)) return extractTar(binFile);
        List<ExtractedFile> customResult = extractCustom(binFile);
        if (!customResult.isEmpty()) return customResult;
        return fallbackCopy(binFile);
    }

    // ========== RPGM 格式解包 ==========
    // 结构:
    //   [0-3]   "RPGM" 魔数
    //   [4-7]   版本/标识
    //   [8-9]   格式类型
    //   [10-13] 图片数量
    //   [14-15] 填充
    //   [16-31] 前导数据(16字节, 可忽略)
    //   [32+]   PNG IHDR 数据(缺 PNG 文件签名和 IHDR 块头)
    //           → 需要补全 8 字节 PNG 签名 + 8 字节 IHDR 块头
    private static final int RPGM_HEADER_SIZE = 32;
    private static final byte[] PNG_MAGIC = {
            (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private static List<ExtractedFile> extractRPGM(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        long fileLen = file.length();

        if (fileLen <= RPGM_HEADER_SIZE) {
            return fallbackCopy(file);
        }

        // 读 RPGM 头的图片数量字段
        byte[] countBytes = readBytes(file, 10, 4);
        int imageCount = 1; // 默认1张
        if (countBytes != null && countBytes.length == 4) {
            imageCount = (countBytes[0] & 0xFF) |
                    ((countBytes[1] & 0xFF) << 8) |
                    ((countBytes[2] & 0xFF) << 16) |
                    ((countBytes[3] & 0xFF) << 24);
            if (imageCount <= 0 || imageCount > 100) imageCount = 1;
        }

        // 读取 IHDR 数据区域
        // 从偏移 32 开始应该是 IHDR 数据
        // 但我们需要判断这是单张图还是多张图打包
        // 先按单张处理: 32 字节后就是 PNG IHDR 数据区

        // ====== 单张图模式（最常见的） ======
        // PNG 格式需要: [签名8] [IHDR块头8+数据13+CRC4] [其他块...]
        // 文件偏移32以后的内容是: IHDR数据(宽高深色共12字节) + 块的继续
        // 我们需要判断块类型从哪里开始并补全PNG签名

        // 实际上更简单: RPGM 格式中，每个块从文件层面就是 PNG 块(缺签名)
        // 检查能否识别后续 PNG 块边界

        if (imageCount <= 1) {
            // 单张图: 跳过 RPGM 头部 32 字节，补上 PNG 签名再重组
            byte[] pngData = rebuildSinglePNG(file);
            if (pngData != null) {
                result.add(new ExtractedFile("image_0.png", pngData.length, pngData));
            }
        } else {
            // 多张图: 按块拆分
            result.addAll(extractRPGMMulti(file));
        }

        if (result.isEmpty()) {
            return fallbackCopy(file);
        }
        return result;
    }

    // 单张图模式: 跳过32字节，将后续内容转为 PNG
    private static byte[] rebuildSinglePNG(File file) throws IOException {
        long fileLen = file.length();
        int dataLen = (int)(fileLen - RPGM_HEADER_SIZE);
        if (dataLen <= 0) return null;

        // 读取 RPGM 头之后的所有数据
        byte[] rawData = readBytes(file, RPGM_HEADER_SIZE, dataLen);
        if (rawData == null || rawData.length < 12) return null;

        // 补全 PNG 签名 + IHDR 块头
        // 从 rawData 偏移 0 开始就是 IHDR 数据: width(4) + height(4) + bit_depth(1) + color_type(1) + ...
        // 标准 PNG 需要: 签名(8) + IHDR长度(4) + "IHDR"(4) + 数据(13) + CRC(4)
        // 然后再接其他块

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(dataLen + 33);

        // 1. PNG 签名
        bos.write(PNG_MAGIC);

        // 2. 从 rawData 提取 IHDR 数据
        int width = ((rawData[0] & 0xFF) << 24) | ((rawData[1] & 0xFF) << 16) |
                    ((rawData[2] & 0xFF) << 8)  | (rawData[3] & 0xFF);
        int height = ((rawData[4] & 0xFF) << 24) | ((rawData[5] & 0xFF) << 16) |
                     ((rawData[6] & 0xFF) << 8)  | (rawData[7] & 0xFF);
        int bitDepth = rawData[8] & 0xFF;
        int colorType = rawData[9] & 0xFF;
        int compression = rawData[10] & 0xFF;
        int filter = rawData[11] & 0xFF;
        int interlace = rawData[12] & 0xFF;

        // 验证合理性
        if (width <= 0 || width > 10000 || height <= 0 || height > 10000 ||
            !isValidBitDepth(bitDepth, colorType) || !isValidColorType(colorType)) {
            return null;
        }

        // 3. 写入 IHDR 块
        byte[] ihdrData = new byte[]{
                (byte)((width >> 24) & 0xFF), (byte)((width >> 16) & 0xFF),
                (byte)((width >> 8) & 0xFF), (byte)(width & 0xFF),
                (byte)((height >> 24) & 0xFF), (byte)((height >> 16) & 0xFF),
                (byte)((height >> 8) & 0xFF), (byte)(height & 0xFF),
                (byte)bitDepth, (byte)colorType,
                (byte)compression, (byte)filter, (byte)interlace
        };

        // IHDR 块: 长度(4) + "IHDR"(4) + 数据(13) + CRC(4)
        writeChunk(bos, "IHDR", ihdrData);

        // 4. 写入剩余块 (IHDR 之后的块，在 rawData 偏移 13 处开始)
        //    rawData[13:] 包含 pHYs, tEXt, IDAT, IEND 等块
        int remainingLen = rawData.length - 13;
        if (remainingLen > 0) {
            bos.write(rawData, 13, remainingLen);
        }

        return bos.toByteArray();
    }

    // 多张图模式
    private static List<ExtractedFile> extractRPGMMulti(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        long fileLen = file.length();
        int offset = RPGM_HEADER_SIZE;
        int index = 0;

        while (offset < fileLen) {
            // 从 offset 开始，先读 4 字节块长度
            byte[] lenBytes = readBytes(file, offset, 4);
            if (lenBytes == null || lenBytes.length < 4) break;

            int chunkLen = (lenBytes[0] & 0xFF) | ((lenBytes[1] & 0xFF) << 8) |
                           ((lenBytes[2] & 0xFF) << 16) | ((lenBytes[3] & 0xFF) << 24);

            if (chunkLen <= 0 || chunkLen > fileLen - offset - 4) {
                // 没有块长度头，尝试把剩余全部读成一个 PNG
                byte[] remaining = readBytes(file, offset, (int)(fileLen - offset));
                if (remaining != null && remaining.length > 12) {
                    byte[] png = rebuildSinglePNGData(remaining);
                    if (png != null) {
                        result.add(new ExtractedFile("image_" + index + ".png", png.length, png));
                    }
                }
                break;
            }

            offset += 4;
            if (offset + chunkLen > fileLen) chunkLen = (int)(fileLen - offset);

            byte[] chunkData = readBytes(file, offset, chunkLen);
            if (chunkData == null) break;

            byte[] png = rebuildSinglePNGData(chunkData);
            if (png != null) {
                result.add(new ExtractedFile("image_" + index + ".png", png.length, png));
                index++;
            }
            offset += chunkLen;
        }
        return result;
    }

    // 从裸 IHDR+ 数据重建完整 PNG
    private static byte[] rebuildSinglePNGData(byte[] rawData) {
        if (rawData.length < 13) return null;

        int w = ((rawData[0] & 0xFF) << 24) | ((rawData[1] & 0xFF) << 16) |
                ((rawData[2] & 0xFF) << 8)  | (rawData[3] & 0xFF);
        int h = ((rawData[4] & 0xFF) << 24) | ((rawData[5] & 0xFF) << 16) |
                ((rawData[6] & 0xFF) << 8)  | (rawData[7] & 0xFF);

        if (w <= 0 || w > 10000 || h <= 0 || h > 10000) return null;

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(rawData.length + 33);
        try {
            bos.write(PNG_MAGIC);

            byte[] ihdrData = new byte[13];
            System.arraycopy(rawData, 0, ihdrData, 0, 13);
            writeChunk(bos, "IHDR", ihdrData);

            if (rawData.length > 13) {
                bos.write(rawData, 13, rawData.length - 13);
            }

            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeChunk(java.io.OutputStream os, String type, byte[] data) throws IOException {
        // 长度
        int len = data != null ? data.length : 0;
        os.write((byte)((len >> 24) & 0xFF));
        os.write((byte)((len >> 16) & 0xFF));
        os.write((byte)((len >> 8) & 0xFF));
        os.write((byte)(len & 0xFF));

        // 类型
        byte[] typeBytes = type.getBytes("ASCII");
        os.write(typeBytes);

        // CRC32(type + data)
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        if (data != null) {
            os.write(data);
            crc.update(data);
        }
        long crcVal = crc.getValue();
        os.write((byte)((crcVal >> 24) & 0xFF));
        os.write((byte)((crcVal >> 16) & 0xFF));
        os.write((byte)((crcVal >> 8) & 0xFF));
        os.write((byte)(crcVal & 0xFF));
    }

    private static boolean isValidBitDepth(int depth, int colorType) {
        switch (colorType) {
            case 0: return depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2:
            case 4:
            case 6: return depth == 8 || depth == 16;
            case 3: return depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default: return false;
        }
    }

    private static boolean isValidColorType(int ct) {
        return ct == 0 || ct == 2 || ct == 3 || ct == 4 || ct == 6;
    }

    // ========== Tar ==========
    private static boolean isTar(File file) throws IOException {
        if (file.length() < 512) return false;
        byte[] buf = readBytes(file, 257, 5);
        if (buf == null) return false;
        return new String(buf, "ASCII").equals("ustar");
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

                StringBuilder sizeStr = new StringBuilder();
                for (int i = 124; i < 136; i++) {
                    if (header[i] == 0 || header[i] == ' ') break;
                    sizeStr.append((char)(header[i] & 0xFF));
                }
                long size = 0;
                try { size = Long.parseLong(sizeStr.toString().trim(), 8); } catch (NumberFormatException e) { continue; }
                if (size > MAX_FILE_SIZE) throw new IOException("Tar too large: " + name);

                if (size > 0) {
                    byte[] data = new byte[(int)size];
                    int off = 0;
                    while (off < size) {
                        int read = raf.read(data, off, (int)Math.min(512, size - off));
                        if (read < 0) break;
                        off += read;
                    }
                    result.add(new ExtractedFile(name, size, data));
                }
                long padding = (512 - (size % 512)) % 512;
                if (padding > 0) raf.skipBytes((int)padding);
            }
        }
        return result;
    }

    // ========== Custom format ==========
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

    // ========== Fallback ==========
    private static List<ExtractedFile> fallbackCopy(File file) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();
        byte[] data = readAll(file, MAX_FILE_SIZE);
        String ext = detectImageExt(data);
        String name = file.getName();
        if (name.toLowerCase().endsWith(".bin") || name.toLowerCase().endsWith(".bin_")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        if (!name.endsWith(ext)) name += ext;
        result.add(new ExtractedFile(name, data.length, data));
        return result;
    }

    private static String detectImageExt(byte[] data) {
        if (data.length < 4) return ".bin";
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return ".png";
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return ".jpg";
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return ".gif";
        if (data[0] == 'B' && data[1] == 'M') return ".bmp";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return ".webp";
        return ".bin";
    }

    private static byte[] readBytes(File file, long offset, int len) throws IOException {
        long fileLen = file.length();
        if (offset >= fileLen) return null;
        if (offset + len > fileLen) len = (int)(fileLen - offset);
        if (len <= 0) return null;
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
        if (len > maxSize) throw new IOException("File too large: " + len);
        byte[] data = new byte[(int)len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int read = fis.read(data, off, data.length - off);
                if (read < 0) throw new IOException("Unexpected EOF");
                off += read;
            }
        }
        return data;
    }
}
