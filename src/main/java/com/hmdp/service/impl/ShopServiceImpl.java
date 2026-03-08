package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
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
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存穿透问题
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺信息不存在");
        }
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

    // 利用互斥锁来解决缓存穿透问题
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;

        try {
            while (true) { // 一直循环获取互斥锁
                // 1. 去redis中去进行查询
                shop = queryRedisById(id);
                if (shop != null)
                    // 2. 缓存命中，直接返回
                    return shop;
                // 3. 缓存未命中，此时进行重建索引
                // 3.1 获取互斥锁
                boolean flag = tryLock(lockKey);
                if (!flag) {
                    // 3.2 此时获取互斥锁失败
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                else {
                    // 3.3 此时获取互斥锁成功
                    break;
                }
            }

            // 4. 完成重建索引的工作，先去数据库查，然后根据结果来重建
            shop = getById(id);
            // 模拟重建延时
            TimeUnit.MILLISECONDS.sleep(200);
            if (shop == null){
                // 4.1 此时是空的，缓存空对象到redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 4.2 此时不是空的，写入redis，并且返回结果
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace(System.out);
            throw new RuntimeException(e);
        } finally {
            // 5. 释放锁
            unLock(lockKey);
        }
        // 返回最终结果
        return shop;
    }

    // 缓存穿透代码留档
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1. redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 查看是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 4. 此时缓存的是空对象
        // ToDo 缓存穿透此时需要判断查到的是否是空值 此时不为null一定是空字符串""
        if (shopJson != null) {
            return null;
        }

        // 5. 进入数据库中进行查询
        Shop shop = getById(id);
        // 6. 不存在就返回错误
        if (shop == null) {
            // ToDo 抵御缓存穿透，将空值写入redis 通过缓存空对象来抵抗缓存穿透(一定要设置过期时间)
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 7. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 20, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

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
