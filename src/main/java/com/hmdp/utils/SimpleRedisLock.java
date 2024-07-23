package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    //锁前缀==key前缀
    private static final String KEY_PREFIX = "lock:";
    //锁名称==key名称
    private final String name;

    private final StringRedisTemplate stringRedisTemplate;

    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setResultType(Long.class);
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        //获取线程标识作为锁标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
    //    @Override
    //public void unlock() {
    //    //获取线程标识
    //    String threadId = ID_PREFIX + Thread.currentThread().getId();
    //    //获取锁中的标识
    //    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //    //判断标识是否一致
    //    if (threadId.equals(id)) {
    //        //释放锁
    //        stringRedisTemplate.delete(KEY_PREFIX + name);
    //    }
    //}
}