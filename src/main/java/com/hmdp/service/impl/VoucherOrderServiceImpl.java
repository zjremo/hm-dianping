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
    @Transactional
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
        // 此时库存充足，减少库存量，然后创建订单
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock())
                .gt("stock", 0)
                .update();
        if (!success){
            // 扣减失败
            return Result.fail("库存不足");
        }

        // 5. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 5.1 订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 5.2 用户ID
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 5.3 代金券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 6. 返回订单ID
        return Result.ok(orderId);
    }
}
