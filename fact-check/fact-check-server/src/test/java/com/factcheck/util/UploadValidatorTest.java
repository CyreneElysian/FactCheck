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
        stubFile(filename, 1024L);

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
        stubFile("exact.png", MAX_SIZE);

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
}
