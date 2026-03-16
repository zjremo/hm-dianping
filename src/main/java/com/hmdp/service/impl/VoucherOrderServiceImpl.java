package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    // version1: 先查库存，然后库存有就直接加，会引发超卖问题
    // version2: 利用乐观锁，先查库存，然后update的时候判断此时库存是否发生变化(结合数据库行锁)，不等的话就表示已经变化不能再更新
    // version3: 版本2的条件太过严苛，其实只要库存大于0就可以添加 -> 版本2和版本3都是将锁下沉至数据库，利用数据库的行锁解决
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 此时秒杀没有开始
            return Result.fail("秒杀尚未开始");
        }
        // 3. 秒杀若开始，判断是否结束，此时判断库存
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 此时秒杀已经结束了
            return Result.fail("秒杀已经结束");
        }

        // 4. 库存充足则扣减库存，创建订单
        if (voucher.getStock() < 1) {
            // 此时库存不足，秒杀只要判断是否有就行了
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // Spring官方不推荐这个方法，建议的是拆Service，然后在这里面注入Service
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    // 一人一单实现问题: 不加锁的时候多个线程进来会都查到没有购买，然后统一进行购买，于是一个用户还是多买了
    // 对userId加锁，此时不再使用乐观锁而是悲观锁，可是userId.toString()单独不能保证因为它是一个新对象，我们需要对常量池中的进行加锁
    // 此函数实现为事务，但是spring的事务管理是针对代理对象的，此方法如果不是接口中的方法就不会经过代理，而是经过了目标对象本身，可是事务是基于代理的
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5. 一人一单实现
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次");
        }

        // 此时库存充足，减少库存量，然后创建订单
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }

        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1 订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2 用户ID
        voucherOrder.setUserId(userId);
        // 6.3 代金券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7. 返回订单ID
        return Result.ok(orderId);
    }
}
