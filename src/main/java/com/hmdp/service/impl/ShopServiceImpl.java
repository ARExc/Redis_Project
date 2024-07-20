package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 封装
     * 解决缓存击穿方案
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
     * 解锁
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
