package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result querySort() {
        // 1. 先从redis缓存中进行查看
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_TYPE_KEY);
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 2. 如果存在返回分类信息
            return Result.ok(JSONUtil.toList(shopTypeJson, ShopType.class));
        }

        // 3. 从数据库里面查
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes == null){
            // 4. 不存在，此时返回错误信息
            return Result.fail("没有分类数据");
        }

        // 5. 数据库中记录存在，则存入redis，然后返回分类信息
        String json = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_TYPE_KEY, json, RedisConstants.SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(shopTypes);
    }
}
