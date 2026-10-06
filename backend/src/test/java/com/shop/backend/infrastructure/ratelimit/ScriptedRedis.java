package com.shop.backend.infrastructure.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.mock;

/** StringRedisTemplate mock whose Lua script executions go to a Handler and are recorded. */
final class ScriptedRedis {

    interface Handler {
        Object handle(String script, List<String> keys, List<String> args);
    }

    record Call(String script, List<String> keys, List<String> args) {
    }

    final List<Call> calls = new ArrayList<>();
    Handler handler = (script, keys, args) -> 0L;

    final StringRedisTemplate template = mock(StringRedisTemplate.class, inv -> {
        Object[] a = inv.getArguments();
        if ("execute".equals(inv.getMethod().getName()) && a.length >= 2 && a[0] instanceof RedisScript<?> script) {
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) a[1];
            List<String> args = new ArrayList<>();
            Arrays.stream(a, 2, a.length).forEach(x -> args.add(String.valueOf(x)));
            calls.add(new Call(script.getScriptAsString(), keys, args));
            return handler.handle(script.getScriptAsString(), keys, args);
        }
        return null;
    });
}
