package com.factcheck.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.factcheck.constant.MessageConstant;
import com.factcheck.exception.BaseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * {@link UploadValidator} 单元测试。
 *
 * <p>锁定的是图片上传的“事实契约”：扩展名白名单（jpg/jpeg/png/webp，大小写不敏感）、
 * 5MB 大小上限、空文件拦截，以及返回的“规范化小写带点扩展名”。
 * 这些规则被上传接口（CommonController /upload）与 OCR 接口共用，
 * 一旦被改动（例如放宽白名单、把 5MB 写成 5*1000*1000、不再小写化），
 * 这里必须立刻变红。</p>
 *
 * <p>本测试完全离线：没有任何 Spring 上下文，MultipartFile 全部由 Mockito mock。</p>
 */
@ExtendWith(MockitoExtension.class)
class UploadValidatorTest {

    /** 生产代码中的上限：5MB = 5 * 1024 * 1024 字节 */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    @Mock
    private MultipartFile file;

    /** JPEG 文件头（含填充，凑够 12 字节，便于对照 WebP 的头部长度） */
    private static final byte[] JPEG_MAGIC = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    /** PNG 文件头：89 50 4E 47 0D 0A 1A 0A + 填充 */
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    /** WebP 文件头：RIFF + 4 字节长度 + WEBP */
    private static final byte[] WEBP_MAGIC = {
            'R', 'I', 'F', 'F', 0x20, 0, 0, 0, 'W', 'E', 'B', 'P'};

    /** 可执行文件（Windows PE）的文件头：MZ…，用于验证"改名成 .jpg 也拦得住" */
    private static final byte[] EXE_MAGIC = {'M', 'Z', (byte) 0x90, 0, 0x03, 0, 0, 0, 4, 0, 0, 0};

    /**
     * 构造一个“非空、指定文件名、指定大小”的上传文件桩。
     * 校验方法内部会依次调用 isEmpty() / getSize() / getOriginalFilename()，三者都会被用到。
     */
    private void stubFile(String originalFilename, long size) {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        when(file.getOriginalFilename()).thenReturn(originalFilename);
    }

    /**
     * 只构造“非空 + 指定大小”的桩：用于命中“大小超限”分支的用例。
     * 该分支在读取文件名之前就抛异常，所以这里不能桩化 getOriginalFilename()，
     * 否则 MockitoExtension 的严格模式会判定为无用桩化（UnnecessaryStubbing）。
     */
    private void stubNonEmptyWithSize(long size) {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
    }

    /**
     * 按扩展名给出一份“内容与扩展名相符”的合法文件头
     */
    private static byte[] magicFor(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return PNG_MAGIC;
        }
        if (lower.endsWith(".webp")) {
            return WEBP_MAGIC;
        }
        return JPEG_MAGIC;
    }

    /**
     * 桩化文件内容（文件头）
     *
     * <p>MultipartFile.getInputStream() 声明了 IOException，
     * 这里包一层 UncheckedIOException，免得每个用例都在签名上再加 throws。</p>
     */
    private void stubContent(byte[] content) {
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 构造一个“合法文件”：文件名合法 + 大小合法 + 文件头与扩展名相符。
     * 只有走到“内容校验”这一步的用例才应该用它（否则会触发 UnnecessaryStubbing）。
     */
    private void stubImage(String originalFilename, long size) {
        stubFile(originalFilename, size);
        stubContent(magicFor(originalFilename));
    }

    /**
     * 合法扩展名必须通过，并返回“小写 + 带点”的规范化扩展名。
     * 其中大写/混合大小写（.JPG/.JpEg）也必须通过并被规范化，
     * 否则对象名会变成 xxx.JPG 这类不受控形式。
     */
    @DisplayName("jpg/jpeg/png/webp 通过，并返回小写带点的规范化扩展名（含大写输入）")
    @ParameterizedTest(name = "[{index}] 文件名={0} 期望扩展名={1}")
    @CsvSource({
            "photo.jpg,   .jpg",
            "photo.jpeg,  .jpeg",
            "photo.png,   .png",
            "photo.webp,  .webp",
            "PHOTO.JPG,   .jpg",
            "PHOTO.JPEG,  .jpeg",
            "Photo.PnG,   .png",
            "photo.WEBP,  .webp",
            "my.photo.v2.jpg, .jpg"
    })
    void shouldAcceptAllowedExtensionsAndReturnNormalizedExtension(String filename, String expectedExtension) {
        stubImage(filename, 1024L);

        String extension = UploadValidator.validateImage(file);

        assertThat(extension).isEqualTo(expectedExtension);
    }

    /**
     * 不在白名单内的扩展名必须被拒，且抛出 BaseException，
     * 消息必须恰好是 UPLOAD_TYPE_NOT_SUPPORTED（前端按文案提示）。
     */
    @DisplayName("gif/exe/txt 等非白名单扩展名被拒，消息为 UPLOAD_TYPE_NOT_SUPPORTED")
    @ParameterizedTest(name = "[{index}] 文件名={0}")
    @ValueSource(strings = {"anim.gif", "evil.exe", "note.txt", "pic.bmp", "archive.zip", "shell.EXE"})
    void shouldRejectUnsupportedExtensions(String filename) {
        stubFile(filename, 1024L);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED);
    }

    /**
     * 没有扩展名的文件名（无点、或以点结尾）必须被拒，
     * 避免出现“直接用原始文件名拼对象名”的路径穿越风险。
     */
    @DisplayName("无扩展名（无点或以点结尾）被拒")
    @ParameterizedTest(name = "[{index}] 文件名={0}")
    @ValueSource(strings = {"noextension", "photo.", "a/b", "dir/"})
    void shouldRejectFilenameWithoutExtension(String filename) {
        stubFile(filename, 1024L);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED);
    }

    /**
     * 空文件（isEmpty() == true）必须被拒，即使文件名看起来完全合法。
     */
    @Test
    @DisplayName("空文件被拒，消息为 UPLOAD_FILE_EMPTY")
    void shouldRejectEmptyFile() {
        when(file.isEmpty()).thenReturn(true);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_FILE_EMPTY);
    }

    /**
     * null 入参也必须走“文件为空”分支，而不是抛 NullPointerException。
     */
    @Test
    @DisplayName("null 入参被拒，消息为 UPLOAD_FILE_EMPTY（不允许 NPE）")
    void shouldRejectNullFile() {
        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(null));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_FILE_EMPTY);
    }

    /**
     * 超过 5MB 必须被拒，消息为 UPLOAD_SIZE_EXCEEDED。
     * 用 5MB + 1 字节，正好卡在边界外侧。
     */
    @Test
    @DisplayName("超过 5MB（5MB+1 字节）被拒，消息为 UPLOAD_SIZE_EXCEEDED")
    void shouldRejectFileLargerThanFiveMegabytes() {
        stubNonEmptyWithSize(MAX_SIZE + 1);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_SIZE_EXCEEDED);
    }

    /**
     * 刚好 5MB 属于允许范围（上限是“不能超过 5MB”，5MB 本身合法）。
     * 这条锁住的是“> 而不是 >=”的边界语义。
     */
    @Test
    @DisplayName("刚好 5MB 通过（边界值闭区间）")
    void shouldAcceptFileExactlyFiveMegabytes() {
        stubImage("exact.png", MAX_SIZE);

        String extension = UploadValidator.validateImage(file);

        assertThat(extension).isEqualTo(".png");
    }

    /**
     * 大小校验先于扩展名校验：即使是非法扩展名，
     * 超大文件也应先报“大小超限”，保证错误提示优先级稳定。
     */
    @Test
    @DisplayName("超大且非法扩展名时，优先报大小超限（校验顺序锁定）")
    void shouldReportSizeErrorBeforeExtensionError() {
        stubNonEmptyWithSize(MAX_SIZE + 1);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_SIZE_EXCEEDED);
    }

    // ------------------------------------------------------------------
    // 文件内容（magic bytes）校验
    // ------------------------------------------------------------------

    /**
     * 把可执行文件改名成 .jpg 是最典型的绕过手段：
     * 扩展名白名单拦不住，必须靠文件头拦。
     */
    @Test
    @DisplayName("可执行文件改名成 .jpg 被拒（扩展名白名单可被改名绕过，内容校验兜住）")
    void shouldRejectExecutableRenamedAsJpg() {
        stubFile("evil.jpg", 1024L);
        stubContent(EXE_MAGIC);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_CONTENT_INVALID);
    }

    /**
     * 内容与扩展名不是同一类图片（PNG 名字、JPEG 内容）也要拒，
     * 避免"用扩展名决定后续处理方式"的逻辑被内容欺骗。
     */
    @Test
    @DisplayName("内容与扩展名不同类（.png 里装 JPEG）被拒")
    void shouldRejectContentNotMatchingExtension() {
        stubFile("photo.png", 1024L);
        stubContent(JPEG_MAGIC);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_CONTENT_INVALID);
    }

    /**
     * 文件头被截断（不足 3 字节）时无法识别类型，按不合法处理。
     */
    @Test
    @DisplayName("文件头被截断（内容过短）被拒")
    void shouldRejectTruncatedHeader() {
        stubFile("photo.jpg", 1024L);
        stubContent(new byte[]{1, 2, 3});

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_CONTENT_INVALID);
    }

    /**
     * RIFF 头正确、但偏移 8 处不是 WEBP（例如 WAVE 音频）时应被拒。
     */
    @Test
    @DisplayName("RIFF 但不是 WEBP（如 WAVE）被拒")
    void shouldRejectRiffThatIsNotWebp() {
        stubFile("sound.webp", 1024L);
        stubContent(new byte[]{'R', 'I', 'F', 'F', 0x20, 0, 0, 0, 'W', 'A', 'V', 'E'});

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_CONTENT_INVALID);
    }

    /**
     * 读不出内容时必须 fail-closed（按不合法处理），而不是放行。
     * 这里不桩化 getInputStream()，Mockito 会返回 null。
     */
    @Test
    @DisplayName("内容流不可读时 fail-closed（不放行）")
    void shouldFailClosedWhenContentUnavailable() {
        stubFile("photo.jpg", 1024L);

        BaseException ex = assertThrows(BaseException.class, () -> UploadValidator.validateImage(file));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.UPLOAD_CONTENT_INVALID);
    }

    /**
     * jpg 与 jpeg 属于同一族，两种扩展名配同一份 JPEG 文件头都应通过。
     */
    @Test
    @DisplayName("jpg 与 jpeg 视为同一族，均可通过内容校验")
    void shouldTreatJpgAndJpegAsSameFamily() {
        stubImage("a.jpg", 1024L);
        assertThat(UploadValidator.validateImage(file)).isEqualTo(".jpg");
    }
}
