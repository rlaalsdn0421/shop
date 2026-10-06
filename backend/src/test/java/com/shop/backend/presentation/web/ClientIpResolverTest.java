package com.shop.backend.presentation.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private MockHttpServletRequest request(String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        for (String line : forwardedFor) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    private String resolve(int hops, String... forwardedFor) {
        return new ClientIpResolver(hops).resolve(request(forwardedFor));
    }

    @Test
    void 성공_항목이_하나면_그_값을_쓴다() {
        assertThat(resolve(1, "9.9.9.9")).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_가장_왼쪽_조작된_값은_무시하고_오른쪽_끝_항목을_쓴다() {
        assertThat(resolve(1, "1.1.1.1, 9.9.9.9")).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_hops가_2면_오른쪽에서_두_번째_항목을_쓴다() {
        assertThat(resolve(2, "1.1.1.1, 9.9.9.9, 8.8.8.8")).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_헤더가_여러_줄이어도_하나의_목록으로_보고_오른쪽_끝을_쓴다() {
        assertThat(resolve(1, "1.1.1.1", "2.2.2.2, 9.9.9.9")).isEqualTo("9.9.9.9");
        assertThat(resolve(2, "1.1.1.1", "2.2.2.2, 9.9.9.9")).isEqualTo("2.2.2.2");
        assertThat(resolve(3, "1.1.1.1", "2.2.2.2, 9.9.9.9")).isEqualTo("1.1.1.1");
    }

    @Test
    void 실패_클라이언트가_보낸_앞_줄은_신뢰하지_않고_프록시가_덧붙인_마지막_줄을_쓴다() {
        assertThat(resolve(1, "6.6.6.6", "9.9.9.9")).isEqualTo("9.9.9.9");
    }

    @Test
    void 성공_헤더가_없으면_remoteAddr을_쓴다() {
        assertThat(resolve(1)).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_헤더가_공백이면_remoteAddr로_대체한다() {
        assertThat(resolve(1, "   ")).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_항목이_hops보다_적으면_remoteAddr로_대체한다() {
        assertThat(resolve(2, "9.9.9.9")).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_선택된_항목이_비어_있거나_너무_길면_remoteAddr로_대체한다() {
        assertThat(resolve(1, "9.9.9.9, ")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "a".repeat(65))).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "1".repeat(65))).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_IP_리터럴이_아닌_값은_remoteAddr로_대체한다() {
        assertThat(resolve(1, "not-an-ip")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "999.1.1.1")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "1.2.3")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "1.2.3.4.5")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "1.2.3.4:80")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "gggg::1")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "1:2:3:4:5:6:7")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "1::2::3")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "[::1]")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "12345::1")).isEqualTo("10.0.0.1");
    }

    @Test
    void 실패_호스트명처럼_보이는_값은_DNS_조회_없이_거절한다() {
        // "localhost" would resolve to 127.0.0.1 if it were looked up; it must fall back instead
        assertThat(resolve(1, "localhost")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "evil.example.com")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "a.b.c.d")).isEqualTo("10.0.0.1");
    }

    @Test
    void 성공_같은_64비트_접두사의_IPv6는_같은_키로_합친다() {
        String a = resolve(1, "2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        String b = resolve(1, "2001:0db8:0001:0002::1");

        assertThat(a).isEqualTo("2001:db8:1:2::/64").isEqualTo(b);
    }

    @Test
    void 성공_접두사가_다른_IPv6는_다른_키가_된다() {
        assertThat(resolve(1, "2001:db8:1:2::1")).isNotEqualTo(resolve(1, "2001:db8:1:3::1"));
    }

    @Test
    void 성공_IPv4_매핑_IPv6는_IPv4_문자열이_된다() {
        assertThat(resolve(1, "::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(resolve(1, "::FFFF:102:304")).isEqualTo("1.2.3.4");
        assertThat(resolve(1, "0:0:0:0:0:ffff:1.2.3.4")).isEqualTo("1.2.3.4");
    }

    @Test
    void 성공_압축_표기와_루프백도_정규화한다() {
        assertThat(resolve(1, "::1")).isEqualTo("0:0:0:0::/64");
        assertThat(resolve(1, "fe80::")).isEqualTo("fe80:0:0:0::/64");
        assertThat(resolve(1, "2001:db8::")).isEqualTo("2001:db8:0:0::/64");
    }

    @Test
    void 성공_IPv4는_앞자리_0을_정리한다() {
        assertThat(resolve(1, "001.002.003.004")).isEqualTo("1.2.3.4");
    }

    @Test
    void 성공_remoteAddr_폴백에도_같은_정규화를_적용한다() {
        MockHttpServletRequest v6 = new MockHttpServletRequest();
        v6.setRemoteAddr("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        MockHttpServletRequest mapped = new MockHttpServletRequest();
        mapped.setRemoteAddr("::ffff:10.0.0.7");
        MockHttpServletRequest zone = new MockHttpServletRequest();
        zone.setRemoteAddr("fe80::1%eth0");

        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(v6)).isEqualTo("2001:db8:1:2::/64");
        assertThat(resolver.resolve(mapped)).isEqualTo("10.0.0.7");
        assertThat(resolver.resolve(zone)).isEqualTo("fe80:0:0:0::/64");
    }

    @Test
    void 실패_hops가_1보다_작으면_헤더를_무시한다() {
        assertThat(resolve(0, "9.9.9.9")).isEqualTo("10.0.0.1");
    }
}
