package com.factcheck.util;

import com.factcheck.constant.MessageConstant;
import com.factcheck.exception.BaseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 上传文件校验工具
 *
 * <p>统一图片上传的校验规则，供上传（CommonController /upload）与 OCR（OcrController /recognize）复用。
 * 校验顺序：非空 → 大小 → 扩展名白名单 → **文件真实内容（magic bytes）**。</p>
 *
 * <p>为什么要校验内容：扩展名只是文件名的一部分，把 {@code evil.exe} 改名成 {@code evil.jpg}
 * 就能通过白名单。这里通过文件头判断真实类型，并要求它与扩展名同类，从而堵住这条绕过路径。
 * 规则见《技术方案》8.1 图片存储与第 10 章安全设计。</p>
 *
 * <p>注意：这是"够用且可解释"的防线，不是完整的文件类型识别库（不解析 MIME、不做图片解码）。
 * 若要更严格，可引入 Apache Tika 之类的库，但会显著增加依赖体积。</p>
 */
public class UploadValidator {

    /**
     * 允许的图片扩展名（小写，不含点）
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp")));

    /**
     * 单文件最大字节数：5MB
     */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /**
     * 读取的头部字节数：足够覆盖 PNG 的 8 字节签名与 WebP 的 12 字节（RIFF + 4 字节长度 + WEBP）
     */
    private static final int HEADER_LENGTH = 12;

    /**
     * JPEG 文件头：FF D8 FF
     */
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /**
     * PNG 文件头：89 50 4E 47 0D 0A 1A 0A
     */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * WebP 文件头的前 4 字节：RIFF
     */
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};

    /**
     * WebP 文件头偏移 8 处的标记：WEBP
     */
    private static final byte[] WEBP_TAG = {'W', 'E', 'B', 'P'};

    private UploadValidator() {
    }

    /**
     * 校验上传的图片文件（扩展名白名单 + 大小限制 + 文件头嗅探）
     *
     * @param file 上传的文件
     * @return 规范化后的扩展名（小写且含点，例如 ".jpg"），可直接用于拼接对象名
     * @throws BaseException 文件为空、超过大小限制、扩展名不在白名单，或文件内容不是同类图片时抛出
     */
    public static String validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BaseException(MessageConstant.UPLOAD_FILE_EMPTY);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BaseException(MessageConstant.UPLOAD_SIZE_EXCEEDED);
        }

        String originalFilename = file.getOriginalFilename();
        int dotIndex = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            throw new BaseException(MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED);
        }

        String extension = originalFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BaseException(MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED);
        }

        // 扩展名可以随便改，所以必须再核对文件真实内容，且要求与扩展名同类
        String detected = detectImageType(file);
        if (detected == null || !detected.equals(imageFamily(extension))) {
            throw new BaseException(MessageConstant.UPLOAD_CONTENT_INVALID);
        }
        return "." + extension;
    }

    /**
     * 把扩展名归一为"图片类型族"：jpg 与 jpeg 视为同一族
     */
    private static String imageFamily(String extension) {
        return "jpg".equals(extension) ? "jpeg" : extension;
    }

    /**
     * 通过文件头判断真实图片类型
     *
     * @return "jpeg" / "png" / "webp"；无法识别、内容读取失败或内容为空时返回 null（按不合法处理）
     */
    private static String detectImageType(MultipartFile file) {
        byte[] header = readHeader(file);
        if (header == null) {
            return null;
        }
        if (startsWith(header, JPEG_MAGIC)) {
            return "jpeg";
        }
        if (startsWith(header, PNG_MAGIC)) {
            return "png";
        }
        if (header.length >= HEADER_LENGTH
                && startsWith(header, RIFF_MAGIC)
                && matchesAt(header, 8, WEBP_TAG)) {
            return "webp";
        }
        return null;
    }

    /**
     * 读取文件开头的若干字节（最多 {@link #HEADER_LENGTH} 个）
     *
     * <p>失败一律返回 null，由调用方按"内容不合法"处理（fail-closed），
     * 而不是放行一个读不出内容的文件。</p>
     */
    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[HEADER_LENGTH];
            int read = 0;
            while (read < HEADER_LENGTH) {
                int n = in.read(buffer, read, HEADER_LENGTH - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return read <= 0 ? null : Arrays.copyOf(buffer, read);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && matchesAt(data, 0, prefix);
    }

    private static boolean matchesAt(byte[] data, int offset, byte[] expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
