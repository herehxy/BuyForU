package com.buyforu.agent.concurrency;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

import static com.buyforu.agent.concurrency.CommandExceptions.*;
import io.micrometer.core.instrument.MeterRegistry;

/** Redis Lua Token Bucket：补充令牌、判断、扣减在一次原子执行中完成。 */
@Component
public class RedisAdmissionController {
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now=tonumber(ARGV[1]); local rate=tonumber(ARGV[2]); local burst=tonumber(ARGV[3]);
            local values=redis.call('HMGET',KEYS[1],'tokens','time');
            local tokens=tonumber(values[1]) or burst; local previous=tonumber(values[2]) or now;
            tokens=math.min(burst,tokens+math.max(0,now-previous)*rate);
            if tokens < 1 then
              redis.call('HSET',KEYS[1],'tokens',tokens,'time',now); redis.call('PEXPIRE',KEYS[1],120000); return 0;
            end
            redis.call('HSET',KEYS[1],'tokens',tokens-1,'time',now); redis.call('PEXPIRE',KEYS[1],120000); return 1;
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ConcurrencyProperties properties;
    private final CircuitBreaker breaker;
    private final MeterRegistry meters;

    public RedisAdmissionController(StringRedisTemplate redis, ConcurrencyProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
        this.breaker = CircuitBreaker.of("redis-coordination", CircuitBreakerConfig.custom()
                .slidingWindowSize(20).minimumNumberOfCalls(10).failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofMillis(80)).slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5)).permittedNumberOfCallsInHalfOpenState(3).build());
    }

    public void admit(String userId, String remoteAddress, AgentCommand.QueueClass lane) {
        if (lane == AgentCommand.QueueClass.CONTROL) {
            try { take("{buyforu}:rate:user:control:" + userId, 30 / 60_000d, 10); }
            catch (AdmissionRejected rejected) { rejected("user", lane); throw rejected; }
            catch (RuntimeException ignored) { return; }
            return;
        }
        try {
            breaker.executeRunnable(() -> {
                int rate = lane == AgentCommand.QueueClass.PLANNING
                        ? properties.planningUserRatePerMinute() : properties.transactionUserRatePerMinute();
                int burst = lane == AgentCommand.QueueClass.PLANNING
                        ? properties.planningUserBurst() : properties.transactionUserBurst();
                take("{buyforu}:rate:user:" + lane + ":" + userId, rate / 60_000d, burst);
                take("{buyforu}:rate:ip:write:" + remoteAddress, 120 / 60_000d, 30);
                take("{buyforu}:rate:global:write", properties.globalWriteRatePerSecond() / 1000d,
                        properties.globalWriteBurst());
            });
            meters.counter("buyforu_admission_total", "result", "accepted", "route_class", lane.name()).increment();
        } catch (AdmissionRejected rejected) {
            rejected("user_or_global", lane);
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw new CoordinationUnavailable(unavailable);
        }
    }

    /** 查询限流故障时放行，保证 Redis 故障不阻断权威状态读取。 */
    public void admitReadBestEffort(String userId, String remoteAddress) {
        try {
            take("{buyforu}:rate:user:read:" + userId, properties.readUserRatePerMinute() / 60_000d,
                    properties.readUserBurst());
            take("{buyforu}:rate:ip:" + remoteAddress, 120 / 60_000d, 30);
        } catch (AdmissionRejected rejected) {
            rejected("read", AgentCommand.QueueClass.CONTROL);
            throw rejected;
        } catch (RuntimeException ignored) {
            // 查询在 Redis 故障时保持可用；写重任务仍由 admit() fail-closed。
        }
    }

    private void rejected(String dimension, AgentCommand.QueueClass lane) {
        meters.counter("buyforu_admission_total", "result", "rejected", "route_class", lane.name()).increment();
        meters.counter("buyforu_rate_limit_rejected_total", "dimension", dimension).increment();
    }

    private void take(String key, double tokensPerMillisecond, int burst) {
        Long accepted = redis.execute(TOKEN_BUCKET, List.of(key), String.valueOf(System.currentTimeMillis()),
                String.valueOf(tokensPerMillisecond), String.valueOf(burst));
        if (!Long.valueOf(1).equals(accepted)) throw new AdmissionRejected("rate limit exceeded", 10);
    }
}
