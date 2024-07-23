package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        //先提交事务，再释放锁，防止这两者间隙的线程不安全
        Long userId = UserHolder.getUser().getId();
        //创建锁对象，以用户ID为锁对象，减少锁定资源的范围
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断锁是否获取成功
        if (!isLock) {
            return Result.fail("不允许重复下单！");
        }
        try {
            //在该方法中直接用this对象调用方法并不会启用该对象的动态代理类对象，也不会使用事务管理方法
            //return this.createVoucherOrder(voucherId);
            //所以以下列方式调用事务方法
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //千万别忘了释放锁
            lock.unlock();
        }

        ////先提交事务，再释放锁，防止这两者间隙的线程不安全
        //Long userId = UserHolder.getUser().getId();
        ////以用户ID为锁对象，减少锁定资源的范围
        //synchronized (userId.toString().intern()) {
        //    //在该方法中直接用this对象调用方法并不会启用该对象的动态代理类对象，也不会使用事务管理方法
        //    //return this.createVoucherOrder(voucherId);
        //    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //    return proxy.createVoucherOrder(voucherId);
        //
        //}
    }

    @Transactional
    //尽量不要直接在方法上上锁，会导致所有进程阻塞，性能极差
    public Result createVoucherOrder(Long voucherId) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2.判断是否存在
        if (count > 0) {
            return Result.fail("不能重复购买！");
        }
        //6.扣减库存
        boolean success = seckillVoucherService
                .update().setSql("stock = stock - 1")   //set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0)  //where id = ? and stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2.用户id
        voucherOrder.setUserId(userId);
        //7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        //7.4保存订单至数据库
        save(voucherOrder);

        //8.返回订单id
        return Result.ok(orderId);
    }
}