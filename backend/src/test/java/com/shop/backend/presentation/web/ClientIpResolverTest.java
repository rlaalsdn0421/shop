package com.shop.backend.presentation.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void 성공_항목이_하나면_그_값을_쓴다() {
        assertThat(new ClientIpResolver(1).resolve(request("9.9.9.9"))).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_가장_왼쪽_조작된_값은_무시하고_오른쪽_끝_항목을_쓴다() {
        assertThat(new ClientIpResolver(1).resolve(request("1.1.1.1, 9.9.9.9"))).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_hops가_2면_오른쪽에서_두_번째_항목을_쓴다() {
        assertThat(new ClientIpResolver(2).resolve(request("1.1.1.1, 9.9.9.9, 8.8.8.8"))).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_헤더가_없으면_remoteAddr을_쓴다() {
        assertThat(new ClientIpResolver(1).resolve(request(null))).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_헤더가_공백이면_remoteAddr로_대체한다() {
        assertThat(new ClientIpResolver(1).resolve(request("   "))).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_항목이_hops보다_적으면_remoteAddr로_대체한다() {
        assertThat(new ClientIpResolver(2).resolve(request("9.9.9.9"))).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_선택된_항목이_비어_있거나_너무_길면_remoteAddr로_대체한다() {
        assertThat(new ClientIpResolver(1).resolve(request("9.9.9.9, "))).isEqualTo("10.0.0.1");
        assertThat(new ClientIpResolver(1).resolve(request("a".repeat(65)))).isEqualTo("10.0.0.1");
    }
}
