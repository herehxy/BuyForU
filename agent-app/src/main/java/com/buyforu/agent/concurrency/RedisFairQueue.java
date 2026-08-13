package com.buyforu.agent.concurrency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.time.Duration;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 用户级等权轮转。每个用户内部 FIFO；active-users ZSET 的虚拟时间确保大用户不能垄断执行槽。
 */
@Component
public class RedisFairQueue {
    private static final DefaultRedisScript<Long> ENQUEUE = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER',KEYS[3],ARGV[2])==1 then return 2 end
            local total=tonumber(redis.call('GET',KEYS[4]) or '0'); if total>=tonumber(ARGV[3]) then return -1 end
            local userCount=redis.call('LLEN',KEYS[2]); if userCount>=tonumber(ARGV[4]) then return -2 end
            redis.call('RPUSH',KEYS[2],ARGV[2]); redis.call('SADD',KEYS[3],ARGV[2]); redis.call('INCR',KEYS[4]);
            if userCount==0 then local vt=tonumber(redis.call('GET',KEYS[5]) or '0'); redis.call('ZADD',KEYS[1],'NX',vt,ARGV[1]); end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> DEQUEUE = new DefaultRedisScript<>("""
            local selected=redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES'); if #selected==0 then return nil end
            local user=selected[1]; local score=tonumber(selected[2]); local listKey=ARGV[1]..user;
            local command=redis.call('LPOP',listKey); if not command then redis.call('ZREM',KEYS[1],user); return nil end
            redis.call('SREM',KEYS[2],command); redis.call('DECR',KEYS[3]); score=score+1; redis.call('SET',KEYS[4],score);
            if redis.call('LLEN',listKey)>0 then redis.call('ZADD',KEYS[1],score,user) else redis.call('ZREM',KEYS[1],user); redis.call('DEL',listKey); end
            return command
            """, String.class);

    private final StringRedisTemplate redis;
    private final ConcurrencyProperties properties;
    private final MeterRegistry meters;

    public RedisFairQueue(StringRedisTemplate redis, ConcurrencyProperties properties, MeterRegistry meters) {
        this.redis = redis; this.properties = properties; this.meters = meters;
        for (AgentCommand.QueueClass lane : new AgentCommand.QueueClass[]{AgentCommand.QueueClass.PLANNING,
                AgentCommand.QueueClass.TRANSACTION}) {
            meters.gauge("buyforu_queue_depth", java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("queue_class", lane.name())), this,
                    queue -> queue.depthValue(lane));
        }
    }

    public void enqueue(AgentCommand command) {
        String lane = command.queueClass().name().toLowerCase();
        int capacity = command.queueClass() == AgentCommand.QueueClass.PLANNING
                ? properties.planningQueueCapacity() : properties.transactionQueueCapacity();
        Long result = redis.execute(ENQUEUE, List.of(active(lane), userList(lane, command.userId()), indexed(lane),
                        depth(lane), virtualTime(lane)), command.userId(), command.commandId().toString(),
                String.valueOf(capacity), String.valueOf(properties.perUserQueueCapacity()));
        if (Long.valueOf(-1).equals(result)) throw new CommandExceptions.AdmissionRejected("queue is full", 10);
        if (Long.valueOf(-2).equals(result)) throw new CommandExceptions.AdmissionRejected("user queue is full", 10);
    }

    public UUID poll(AgentCommand.QueueClass queueClass) {
        String lane = queueClass.name().toLowerCase();
        String value = redis.execute(DEQUEUE, List.of(active(lane), indexed(lane), depth(lane), virtualTime(lane)),
                "{buyforu}:queue:" + lane + ":user:");
        return value == null ? null : UUID.fromString(value);
    }

    /** 分布式用户执行许可，防止同一用户的不同 run 同时占用多个昂贵下游槽位。 */
    public boolean tryAcquireUser(String userId, UUID commandId) {
        // 许可只比租约多一个心跳，避免 release 失败后把用户堵 5 分钟。
        Duration ttl = properties.leaseDuration().plus(properties.leaseHeartbeat());
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("{buyforu}:running:user:" + userId,
                commandId.toString(), ttl));
    }

    public void releaseUser(String userId, UUID commandId) {
        String key = "{buyforu}:running:user:" + userId;
        String owner = redis.opsForValue().get(key);
        if (commandId.toString().equals(owner)) redis.delete(key);
    }

    private static String active(String lane) { return "{buyforu}:queue:" + lane + ":active-users"; }
    private static String indexed(String lane) { return "{buyforu}:queue:" + lane + ":indexed"; }
    private static String depth(String lane) { return "{buyforu}:queue:" + lane + ":depth"; }
    private static String virtualTime(String lane) { return "{buyforu}:queue:" + lane + ":virtual-time"; }
    private static String userList(String lane, String user) { return "{buyforu}:queue:" + lane + ":user:" + user; }

    private double depthValue(AgentCommand.QueueClass lane) {
        try {
            String value = redis.opsForValue().get(depth(lane.name().toLowerCase()));
            return value == null ? 0 : Double.parseDouble(value);
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
