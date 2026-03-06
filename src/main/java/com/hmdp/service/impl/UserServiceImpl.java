package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone))
                // 2. 如果不符合，返回错误信息
                return Result.fail("手机格式错误");

        // 3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);


        // 4. 保存验证码到session里面
//        session.setAttribute("code", code);
        // ToDo 4. 向redis里面存储key-value, 设置过期时间为2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码 (模拟)
        log.debug("发送短信验证码成功，验证码: {}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone))
            // 校验不成功，直接返回错误信息
            return Result.fail("手机号格式错误");

        // 2. 校验验证码 传统session中获取
//        Object cacheCode = session.getAttribute("code");
        // ToDo 2. 校验验证码  -> redis中进行获取
        String cacheCode =  stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code))
            // 3. 不一致就直接报错
            return Result.fail("验证码错误");

//        if (cacheCode == null || !cacheCode.equals(code.toString()))
//            return Result.fail("验证码错误");
        // 4. 一致就拿手机号去查询用户，有可能查到，有可能没有查到 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        if (Objects.isNull(user)){
            // 5. 没有用户就新创建用户，保存用户到数据库
            user = createUserWithPhone(phone);
        }
        // 6. 用户存在就不用管
        // 7. 最后将用户加入到session -> 保存用户信息到redis中
//        session.setAttribute("user", user);
        // ToDo 7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // ToDo 7.2 user对象转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // ToDo 7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        // ***注意***: 这里一定要注意，我们最后使用的是string类型的序列化器，所以这里的值如果不是string类型会报错，
        // 我们在这里要手动指定类型转换为string都
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // ToDo 7.4 设置有效期 避免存储爆炸
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8. 返回
//        return Result.ok();

        // ToDo 8 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // 2. 保存用户
        save(user);
        return user;
    }
}
