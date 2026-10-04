package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.factcheck.context.BaseContext;
import com.factcheck.dto.WechatBindDTO;
import com.factcheck.exception.RateLimitException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import com.factcheck.service.HotRankService;
import com.factcheck.service.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 微信绑定（{@code POST /user/wechat/bind}）的**限流契约测试**。
 *
 * <h3>为什么这个接口必须限流</h3>
 * <ul>
 *   <li>它在鉴权**白名单**里（{@code WebMvcConfiguration} 的 excludePathPatterns），未登录即可调用；</li>
 *   <li>每次调用都会带 {@code code} 请求微信 {@code code2session}——
 *       不限流等于给外部一个"刷微信接口"的跳板；</li>
 *   <li>注册早就按 IP 限流了，绑定当时没有，属于同一种缺口。</li>
 * </ul>
 *
 * <h3>只断言两条能离线确知的契约</h3>
 * <ol>
 *   <li><b>限流先于任何业务动作</b>：超限时直接抛 {@link RateLimitException}，
 *       此时**不应查库**（说明没走到绑定逻辑，更没请求微信）。</li>
 *   <li><b>每次调用都会计数</b>：限流器被调用的次数 = 绑定请求次数。</li>
 * </ol>
 * 不断言"成功路径"——那需要真实 HTTP 与微信返回，属于集成测试范畴（离线环境做不到）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplWechatBindRateLimitTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private WeChatProperties weChatProperties;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private HotRankService hotRankService;

    @InjectMocks
    private UserServiceImpl userService;

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private WechatBindDTO dto(String username, String code) {
        WechatBindDTO d = new WechatBindDTO();
        d.setUsername(username);
        d.setCode(code);
        return d;
    }

    @Test
    @DisplayName("超限：抛限流异常，且不查库（证明限流先于绑定逻辑，也就没请求微信）")
    void shouldRejectWhenRateLimitedBeforeAnyBusinessAction() {
        doThrow(new RateLimitException("操作过于频繁，请稍后再试"))
                .when(rateLimitService).checkWechatBind(any());

        assertThatThrownBy(() -> userService.bindWechat(dto("alice", "fake-code")))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("操作过于频繁");

        verify(userMapper, never()).getByUsername(anyString());
        verify(userMapper, never()).getByOpenid(anyString());
    }

    @Test
    @DisplayName("每个绑定请求都先过限流：不同用户名各计一次")
    void shouldCheckRateLimitOncePerRequest() {
        doThrow(new RateLimitException("操作过于频繁，请稍后再试"))
                .when(rateLimitService).checkWechatBind(any());

        assertThatThrownBy(() -> userService.bindWechat(dto("alice", "c1")))
                .isInstanceOf(RateLimitException.class);
        assertThatThrownBy(() -> userService.bindWechat(dto("bob", "c2")))
                .isInstanceOf(RateLimitException.class);

        // 两次请求 = 两次限流检查（按 IP 计数，与用户名无关）
        verify(rateLimitService, org.mockito.Mockito.times(2)).checkWechatBind(any());
    }
}
