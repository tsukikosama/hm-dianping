-- 通过lua脚本实现锁的释放
-- redis.call redis的命令  keys 用来存key的  argv 用来存其他的参数的
if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0