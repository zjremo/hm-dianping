package com.hmdp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderNoId {
    private Long timeStamp;
    private Long count;
    private Long voucherId;
    private Long userId;
}
