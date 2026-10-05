package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.fastjson.JSONObject;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.WechatRegisterDTO;
import com.factcheck.entity.User;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import com.factcheck.service.RateLimitService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 小程序端「创建新账号」的契约测试
 *
 * <h3>要修的问题</h3>
 * 小程序端此前在"该微信未绑定任何账号"时**只给一条路**：输入 Web 端已有的用户名/密码去绑定。
 * 没有 Web 账号的新用户被直接挡住 —— 产品文档 S11 只写了"绑定 Web 账号"，
 * 但"新用户无法注册"是明显缺口。现在小程序端让用户自己选：
 * <b>创建新账号</b> 或 <b>绑定已有账号</b>。
 *
 * <h3>三条必须守住的底线</h3>
 * <ol>
 *   <li><b>注册 + 绑定是一个事务</b>：分两步做的话，绑定失败会留下
 *       "账号存在但没绑微信"的中间态 —— 用户不知道注册成功了，再试一次撞
 *       "用户名已存在"，用微信登录也仍然进不去。</li>
 *   <li><b>同一微信不能注册第二个账号</b>：否则一个人可以无限多开小号。</li>
 *   <li><b>与 Web 注册共用同一套校验</b>：否则弱口令会从小程序端绕进来。</li>
 * </ol>
 *
 * <h3>为什么用子类覆写而不是 mockStatic</h3>
 * 微信 openid 相关分支要拦 {@code HttpClientUtil.doGet}。项目可用的 Mockito 是
 * 4.5.1 的 {@code mockito-core}，其默认 MockMaker **不支持静态 mock**
 * （实测报错：{@code SubclassByteBuddyMockMaker does not support the creation of
 * static mocks}），启用需要引入离线仓库里没有的 {@code mockito-inline}。
 * 因此改为在 {@link UserServiceImpl#callWechatCode2Session} 这个
 * **protected 测试接缝**上覆写 —— 不引入新依赖，也不依赖字节码插桩。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WechatRegisterTest {

    private static final String OPENID = "o_test_openid_1234567890";
    private static final String CODE = "wx-code-abc";

    @Mock
    private UserMapper userMapper;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private WeChatProperties weChatProperties;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(1L);
        userService = new TestableUserServiceImpl();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "rateLimitService", rateLimitService);
        ReflectionTestUtils.setField(userService, "weChatProperties", weChatProperties);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    /**
     * 测试替身：把"调微信换 openid"替换掉，其余逻辑走真实实现。
     *
     * <p>{@code openidToReturn} 为 null 表示模拟"微信没返回 openid"。</p>
     */
    private static class TestableUserServiceImpl extends UserServiceImpl {
        String openidToReturn = OPENID;

        @Override
        protected JSONObject callWechatCode2Session(String code) {
            JSONObject session = new JSONObject();
            if (openidToReturn != null) {
                session.put("openid", openidToReturn);
                session.put("unionid", "u_union_1");
            }
            return session;
        }
    }

    private TestableUserServiceImpl testable() {
        return (TestableUserServiceImpl) userService;
    }

    private WechatRegisterDTO dto(String username, String password, String code) {
        WechatRegisterDTO d = new WechatRegisterDTO();
        d.setUsername(username);
        d.setPassword(password);
        d.setCode(code);
        return d;
    }

    /**
     * 桩 {@code userMapper.insert}：真实 MyBatis 会回填自增主键，mock 不会。
     * 不回填的话 {@code registerByWechat} 末尾的 {@code getById(user.getId())}
     * 会拿到 null —— 这是单测与真实行为的一个已知差异，必须显式补上。
     */
    private void stubInsertAssignsId(long id) {
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(id);
            return 1;
        }).when(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("registerByWechat 必须标注 @Transactional（注册与绑定要原子）")
    void mustBeTransactional() throws Exception {
        Method m = UserServiceImpl.class.getMethod("registerByWechat", WechatRegisterDTO.class);
        Transactional annotation = m.getAnnotation(Transactional.class);
        assertThat(annotation)
                .as("注册+绑定必须同一事务，否则绑定失败会留下"
                        + "「账号已建但没绑微信」的中间态")
                .isNotNull();
        assertThat(annotation.rollbackFor()).contains(Exception.class);
    }

    @Test
    @DisplayName("用户名已存在 → 拒绝注册（与 Web 注册同一套唯一性校验）")
    void shouldRejectExistingUsername() {
        when(userMapper.getByUsername("alice")).thenReturn(new User());

        assertThatThrownBy(() -> userService.registerByWechat(dto("alice", "abcd1234", null)))
                .isInstanceOf(BaseException.class);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("弱口令 → 拒绝（后端规则：≥8 位且含字母与数字）")
    void shouldRejectWeakPassword() {
        assertThatThrownBy(() -> userService.registerByWechat(dto("bob", "abc1", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("8");
        assertThatThrownBy(() -> userService.registerByWechat(dto("bob", "12345678", null)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> userService.registerByWechat(dto("bob", "abcdefgh", null)))
                .isInstanceOf(BaseException.class);

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("小程序端注册同样受注册限流约束（不能成为绕过限流的旁路）")
    void shouldShareRegisterRateLimit() {
        when(userMapper.getByUsername("carol")).thenReturn(new User());

        assertThatCode(() -> {
            try {
                userService.registerByWechat(dto("carol", "abcd1234", null));
            } catch (BaseException ignored) {
                // 用户名重复不是本用例关注点
            }
        }).doesNotThrowAnyException();

        // 用 nullable(String.class)：单测里没有 Web 请求上下文，
        // ClientIpUtils.currentIp() 返回 null，anyString() 会匹配不上。
        verify(rateLimitService).checkRegister(nullable(String.class));
    }

    @Test
    @DisplayName("不传 code：只注册、不绑定（Web 端语义不变）")
    void withoutCodeShouldNotTouchWechat() {
        when(userMapper.getByUsername("dave")).thenReturn(null);
        stubInsertAssignsId(9L);
        when(userMapper.getById(9L)).thenReturn(User.builder().id(9L).username("dave").build());

        User result = userService.registerByWechat(dto("dave", "abcd1234", null));

        assertThat(result.getId()).isEqualTo(9L);
        verify(userMapper, never()).update(any(User.class));
    }

    @Test
    @DisplayName("昵称为空时取用户名")
    void nicknameDefaultsToUsername() {
        when(userMapper.getByUsername("erin")).thenReturn(null);
        stubInsertAssignsId(10L);
        when(userMapper.getById(10L)).thenReturn(User.builder().id(10L).username("erin").build());

        userService.registerByWechat(dto("erin", "abcd1234", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("erin");
        assertThat(captor.getValue().getRole()).isEqualTo(0);
    }

    @Test
    @DisplayName("★ 带 code：注册成功后把该微信绑到新账号（同一次请求完成）")
    void withCodeShouldBindWechatToNewUser() {
        when(userMapper.getByUsername("frank")).thenReturn(null);
        when(userMapper.getByOpenid(OPENID)).thenReturn(null);   // 该微信未绑过
        stubInsertAssignsId(11L);
        when(userMapper.getById(11L)).thenReturn(
                User.builder().id(11L).username("frank").wechatOpenid(OPENID).build());

        User result = userService.registerByWechat(dto("frank", "abcd1234", CODE));

        assertThat(result.getWechatOpenid())
                .as("返回的 User 必须带上刚写入的 openid（前端据此判断是否已绑定）")
                .isEqualTo(OPENID);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).update(captor.capture());
        assertThat(captor.getValue().getWechatOpenid()).isEqualTo(OPENID);
        assertThat(captor.getValue().getWechatUnionid()).isEqualTo("u_union_1");
    }

    @Test
    @DisplayName("★ 带 code 但该微信已绑过账号 → 拒绝注册（防止一个人多开小号）")
    void withCodeShouldRejectAlreadyBoundWechat() {
        when(userMapper.getByOpenid(OPENID))
                .thenReturn(User.builder().id(1L).username("other").build());

        assertThatThrownBy(() -> userService.registerByWechat(dto("grace", "abcd1234", CODE)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("已绑定");

        // 关键：不能建号，也不能改绑
        verify(userMapper, never()).insert(any(User.class));
        verify(userMapper, never()).update(any(User.class));
    }

    @Test
    @DisplayName("带 code 但微信没返回 openid → 按微信登录失败处理，不建号")
    void withCodeShouldFailWhenOpenidMissing() {
        testable().openidToReturn = null;

        assertThatThrownBy(() -> userService.registerByWechat(dto("henry", "abcd1234", CODE)))
                .isInstanceOf(BaseException.class);

        verify(userMapper, never()).insert(any(User.class));
    }
}
