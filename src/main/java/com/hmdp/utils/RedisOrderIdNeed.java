package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisOrderIdNeed {
    private Long timeStamp;
    private String orderIDKey;
}
