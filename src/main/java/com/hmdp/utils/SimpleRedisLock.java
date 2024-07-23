package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        //获取线程标识
        String threadName = Thread.currentThread().getName();
        //获取锁
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadName, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
