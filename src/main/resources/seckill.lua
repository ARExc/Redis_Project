--- 1.参数列表
--- 1.1.优惠券id
local voucherId = ARGV[1]
--- 1.2.用户id
local userId = ARGV[2]
--- 1.3.订单id
local orderId = ARGV[3]

--- 2.数据key
--- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
--- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

--- 脚本业务
--- 3.1.判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    --- 3.2.库存不足，返回1
    return 1
end

--- 3.3.判断用户是否已下过单
if (redis.call('sismember', orderKey, userId) == 1) then
    --- 3.4.已下过单，返回2
    return 2
end

--- 3.5.扣减库存
redis.call('incrby', stockKey, -1)
--- 3.6.将userId写入订单key中
redis.call('sadd', orderKey, userId)
--- 3.7.发送消息到消息队列中，XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
--- 4.下单成功，返回0
return 0