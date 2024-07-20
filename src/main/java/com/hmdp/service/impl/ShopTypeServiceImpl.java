package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //1.从Redis中查询缓存
        String typeList = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOPLIST_KEY);
        //2.判断是否存在
        if(StrUtil.isNotBlank(typeList)){
            //3.存在,转换为List后返回
            List<ShopType> shopTypes = JSONUtil.toList(typeList, ShopType.class);
            return Result.ok(shopTypes);
        }
        //4.不存在，从数据库中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //5.数据库中不存在，返回错误
        if(shopTypes == null){
            return Result.fail("分类不存在");
        }
        //6.存在，将数据放入Redis缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOPLIST_KEY, JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
