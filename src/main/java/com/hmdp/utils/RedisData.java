package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;
    // 采用组合的方式来存储对象
    private Object data;
}
