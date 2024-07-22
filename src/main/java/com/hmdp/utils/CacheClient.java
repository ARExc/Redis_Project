package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long Time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), Time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long Time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(unit.toMinutes(Time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> clazz, Function<ID,R> dbFallBack, Long time , TimeUnit unit){
        String key = keyPrefix + id;
        //1.从Redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在则返回
            return JSONUtil.toBean(json, clazz);
        }
        //判断命中的是否为空串
        if(json != null){
            return null;
        }
        //4.不存在，根据id从数据库中查询
        R r = dbFallBack.apply(id);
        if(r == null){
            //5.不存在，返回错误
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入Redis内存
        this.set(key, r, time, unit);
        //7.返回
        return r;
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> clazz, Function<ID,R> dbFallBack, Long time , TimeUnit unit){
        String key = keyPrefix + id;
        //1.从Redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(json)){
            //3.不存在，直接返回错误
            return null;
        }
        //4.命中，反序列化JSON为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1.未过期，直接返回店铺信息
            return r;
        }
        //5.2.已过期，开始缓存重建

        //6.缓存重建
        //6.1.获取互斥锁
        String lockKey = lockKeyPrefix + id ;
        //6.2.判断是否获取成功
        if(tryLock(lockKey)) {
            //DoubleCheck
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                r = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
                return r;
            }
            //6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r_new = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key, r_new, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //6.4.否则返回过期的商铺信息
        return r;
    }

    private Boolean tryLock(String key){
        return stringRedisTemplate.opsForValue().setIfAbsent(key,"1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
