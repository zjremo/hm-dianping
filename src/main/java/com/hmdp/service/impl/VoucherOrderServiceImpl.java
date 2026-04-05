package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderNoId;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RedisOrderIdNeed;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>();

    static {
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua")); // 设置脚本路径
        SECKILL_SCRIPT.setResultType(Long.class); // 设置返回类型
    }

    // JVM阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 线程池 —— 完成下单任务落库
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 代理对象
    private IVoucherOrderService proxy;

    private class VoucherOrderHandler implements Runnable {

/*
        // JVM阻塞队列run方法
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 去执行落库操作
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
*/
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    // 1. 获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        // 如果获取失败，说明没有消息，进行下一次循环
                        continue;
                    }

                    // 3. 如果获取成功，可以下单落库
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrderNoId voucherOrderNoId = BeanUtil.fillBeanWithMap(values, new VoucherOrderNoId(), true);

                    // 3.1. 组装为VoucherOrder
                    Long timeStamp = voucherOrderNoId.getTimeStamp();
                    Long count = voucherOrderNoId.getCount();
                    Long orderId = redisIdWorker.nextId(timeStamp, count);

                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setId(orderId);
                    voucherOrder.setUserId(voucherOrderNoId.getUserId());
                    voucherOrder.setVoucherId(voucherOrderNoId.getVoucherId());

                    // 3.2 下单落库
                    handleVoucherOrder(voucherOrder);

                    // 4. ACK确认此条信息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e){
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList(){
            while (true){
                try {
                    // 1. 获取pending-list中的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. pending-list中没有消息，直接退出
                    if (list == null || list.isEmpty()){
                        // 如果获取失败，说明没有消息，进行下一次循环
                        break;
                    }

                    // 3. 如果获取成功，可以下单落库
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrderNoId voucherOrderNoId = BeanUtil.fillBeanWithMap(values, new VoucherOrderNoId(), true);

                    // 3.1. 组装为VoucherOrder
                    Long timeStamp = voucherOrderNoId.getTimeStamp();
                    Long count = voucherOrderNoId.getCount();
                    Long orderId = redisIdWorker.nextId(timeStamp, count);

                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setId(orderId);
                    voucherOrder.setUserId(voucherOrderNoId.getUserId());
                    voucherOrder.setVoucherId(voucherOrderNoId.getVoucherId());

                    // 3.2 下单落库
                    handleVoucherOrder(voucherOrder);

                    // 4. ACK确认此条信息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e){
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace(System.out);
                    }
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        proxy.createVoucherOrder(voucherOrder);
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 秒杀是否结束
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 此时秒杀没有开始
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 此时秒杀已经结束了
            return Result.fail("秒杀已经结束");
        }

        // 2. lua脚本判断是否拥有下单资格
        Long userId = UserHolder.getUser().getId();
        RedisOrderIdNeed redisOrderIdNeed = redisIdWorker.nextIdNeed("order");
        // 3. 根据结果来构建返回结果，如果有则异步下单
        long r = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), redisOrderIdNeed.getOrderIDKey(), redisOrderIdNeed.getTimeStamp().toString());
        if (r < 3) {
            // 3.1 无法下单
            return Result.fail(r == 1 ? "库存不足" : "单用户不能重复下单");
        }

        // 此时我们拿到count值结合已经知道的timestamp拼接得到orderId
        long orderId = redisIdWorker.nextId(redisOrderIdNeed.getTimeStamp(), r - 3);

//        // 3.2 有下单资格，此时直接返回OrderID
//        long orderId = redisIdWorker.nextId("order");

//        // TODO: 异步下单，任务放入阻塞队列，后续由线程池中的线程去完成
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
        // 拿到代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    // version1: 先查库存，然后库存有就直接加，会引发超卖问题
    // version2: 利用乐观锁，先查库存，然后update的时候判断此时库存是否发生变化(结合数据库行锁)，不等的话就表示已经变化不能再更新
    // version3: 版本2的条件太过严苛，其实只要库存大于0就可以添加 -> 版本2和版本3都是将锁下沉至数据库，利用数据库的行锁解决
/*
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

        // 使用Redisson分布式锁，不指定参数，直接使用看门狗机制
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();

        if (!isLock){
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象，使得相应逻辑走事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

//        synchronized (userId.toString().intern()) {
//            // Spring官方不推荐这个方法，建议的是拆Service，然后在这里面注入Service
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
    }
*/

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

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 此时这里的voucherOrder是从阻塞队列里面拿的，已经封装好了的
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存扣减失败");
            return ;
        }

        save(voucherOrder);
    }
}
