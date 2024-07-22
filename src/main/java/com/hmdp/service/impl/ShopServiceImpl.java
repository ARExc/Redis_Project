package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺id
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透方案
        //Shop shop = queryWithPassThrough(id);

        //解决缓存击穿方案——互斥锁
        Shop shop = queryWithMutex(id);

        //解决缓存击穿方案——逻辑过期
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 封装
     * 解决缓存击穿方案——逻辑过期
     * @param id
     * @return
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从Redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //3.不存在，直接返回错误
            return null;
        }
        //4.命中，反序列化JSON为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
        //5.1.未过期，直接返回店铺信息
            return shop;
        }
        //5.2.已过期，开始缓存重建

        //6.缓存重建
        //6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id ;
        //6.2.判断是否获取成功
        if(tryLock(lockKey)) {
            //DoubleCheck
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }
            //6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.savaShop2Redis(id , 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //6.4.否则返回过期的商铺信息
        return shop;
    }

    /**
     * 更新重建缓存
     * @param id
     * @param expireMinutes
     */
    private void savaShop2Redis(Long id, Long expireMinutes){
        //1.查询相应店铺
        Shop shop = getById(id);
        //2.封装店铺信息
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 封装
     * 解决缓存击穿方案——互斥锁
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){

        String key = CACHE_SHOP_KEY + id;

        //1.从Redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在则返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否为空串
        if(shopJson != null){
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;

        try {
            //4.实现缓存重建
            //4.1.获取互斥锁
            Boolean isLock = tryLock(lockKey);
            //4.2.判断是否获取成功
            if (!isLock){
                //4.3.失败，休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4.DoubleCheck
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if(shopJson != null){
                return null;
            }
            //4.5.成功，根据id从数据库中查询
            shop = getById(id);
            if(shop == null){
                //不存在，返回错误
                stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入Redis内存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unLock(lockKey);
        }

        //8.返回
        return shop;
    }

    /**
     * 封装
     * 解决缓存穿透方案
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从Redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在则返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空串
        if(shopJson != null){
            return null;
        }
        //4.不存在，根据id从数据库中查询
        Shop shop = getById(id);
        if(shop == null){
            //5.不存在，返回错误
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入Redis内存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    /**
     * 上锁
     * @param key
     * @return
     */
    private Boolean tryLock(String key){
        return stringRedisTemplate.opsForValue().setIfAbsent(key,"1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 一致更新缓存与数据库
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        //1.判断id不为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空！");
        }
        //2.更新数据库
        updateById(shop);
        //3.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
