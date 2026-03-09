package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿问题
//        Shop shop = queryWithMutex(id);
//        if (shop == null) {
//            return Result.fail("店铺信息不存在");
//        }

        // 逻辑删除解决缓存击穿问题
//        Shop shop = queryWithLogicalExpire(id);
//        if (shop == null)
//            return Result.fail("店铺不存在! ");

        // 工具类解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 工具类使用逻辑过期来解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.LOCK_SHOP_TTL,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.SECONDS
        );
        // 返回最终结果
        return Result.ok(shop);
    }

    public Shop queryRedisById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1. redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 查看是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 4. 剩下的情况只有可能是空字符串或者null
        return null;
    }

//    // 利用逻辑删除解决缓存击穿问题
//    public Shop queryWithLogicalExpire(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        String locKey = RedisConstants.LOCK_SHOP_KEY + id;
//        // 1. redis获取数据
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2. 判断是否存在，不存在直接返回空
//        if (StrUtil.isBlank(shopJson)) {
//            // 2.1 此时不存在，直接返回空值
//            return null;
//        }
//
//        // 3. 此时redis中命中缓存，先解析对象，再读取逻辑过期时间
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        // 这里的data对象其实底层是JsonObject, 因为我们本来就是object类型，反序列化器不知道反序列化为啥
//        // 于是类默认序列化为了JsonObject
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        // 获取过期时间
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 4. 判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())){
//            // 4.1 此时代表还没过期，数据可以直接用
//            return shop;
//        }
//        // 5. 此时数据已经过期了，需要竞争互斥锁，成功的话会开启一个线程来完成查表重建缓存动作
//        boolean isLock = tryLock(locKey);
//        if (isLock){
//            // 拿到互斥锁，开启新线程来完成缓存重建动作
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 重建缓存
//                    this.saveShop2Redis(id, 10L);
//                    log.debug("saveShop2Redis is successful");
//                } catch (Exception e){
//                    e.printStackTrace(System.out);
//                    throw new RuntimeException(e);
//                } finally {
//                    // 释放锁
//                    unLock(locKey);
//                }
//            });
//            log.debug("the shop return is older, {}", shop);
//        }
//
//        // 6. 返回过期的商铺信息
//        return shop;
//    }

    // 利用互斥锁来解决缓存击穿问题
//    public Shop queryWithMutex(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
//        Shop shop = null;
//
//        try {
//            while (true) { // 一直循环获取互斥锁
//                // 1. 去redis中去进行查询
//                shop = queryRedisById(id);
//                if (shop != null)
//                    // 2. 缓存命中，直接返回
//                    return shop;
//                // 3. 缓存未命中，此时进行重建索引
//                // 3.1 获取互斥锁
//                boolean flag = tryLock(lockKey);
//                if (!flag) {
//                    // 3.2 此时获取互斥锁失败
//                    TimeUnit.MILLISECONDS.sleep(50);
//                } else {
//                    // 3.3 此时获取互斥锁成功
//                    break;
//                }
//            }
//
//            // 4. 完成重建索引的工作，先去数据库查，然后根据结果来重建
//            shop = getById(id);
//            // 模拟重建延时
//            TimeUnit.MILLISECONDS.sleep(200);
//            if (shop == null) {
//                // 4.1 此时是空的，缓存空对象到redis
//                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//
//            // 4.2 此时不是空的，写入redis，并且返回结果
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            e.printStackTrace(System.out);
//            throw new RuntimeException(e);
//        } finally {
//            // 5. 释放锁
//            unLock(lockKey);
//        }
//        // 返回最终结果
//        return shop;
//    }

    // 缓存穿透代码留档
//    public Shop queryWithPassThrough(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 1. redis中查询商铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2. 查看是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3. 存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 4. 此时缓存的是空对象
//        // ToDo 缓存穿透此时需要判断查到的是否是空值 此时不为null一定是空字符串""
//        if (shopJson != null) {
//            return null;
//        }
//
//        // 5. 进入数据库中进行查询
//        Shop shop = getById(id);
//        // 6. 不存在就返回错误
//        if (shop == null) {
//            // ToDo 抵御缓存穿透，将空值写入redis 通过缓存空对象来抵抗缓存穿透(一定要设置过期时间)
//            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//
//        // 7. 存在，写入redis
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unLock(String key) {
//        stringRedisTemplate.delete(key);
//    }
//
//    // 缓存预热
//    public void saveShop2Redis(Long id, Long expireSeconds) {
//        try {
//            // 模拟重建索引延迟
//            TimeUnit.MILLISECONDS.sleep(200);
//            String key = RedisConstants.CACHE_SHOP_KEY + id;
//            // 1. 查询店铺数据
//            Shop shop = getById(id);
//            // 2. 封装逻辑过期时间
//            RedisData redisData = new RedisData();
//            redisData.setData(shop);
//            redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//            // 3. 写入redis
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
//        } catch (InterruptedException e) {
//            e.printStackTrace(System.out);
//            throw new RuntimeException(e);
//        }
//    }

    // 更新数据库就删除缓存，避免大量的无效更新缓存操作
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
