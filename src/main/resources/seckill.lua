-- 1. 参数列表
-- 1.1 voucherID
local voucherID = ARGV[1]
-- 1.2 userID
local userID = ARGV[2]

-- 2. keys
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherID

-- 2.2 订单key
local orderKey = 'seckill:order:' .. voucherID

-- 3. 脚本业务
-- 3.1 判断库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 3.2 用户是否下单
if (redis.call('SISMEMBER', orderKey, userID) == 1) then
    -- 此时用户已经下单过
    return 2
end

-- 3.3 拥有了下单资格现在，开始扣减库存和添加下单用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userID)
return 0