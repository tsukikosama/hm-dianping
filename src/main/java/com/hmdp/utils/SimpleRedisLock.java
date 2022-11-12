package com.hmdp.utils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author 10833
 */
public  class SimpleRedisLock implements ILock{

    public StringRedisTemplate redisTemplate;
    public String userName;

    public SimpleRedisLock(StringRedisTemplate redisTemplate, String Name) {
        this.redisTemplate = redisTemplate;
        this.userName = Name;
    }

    public static final String KEY_NAME = "lock:";
    //用来创建复杂的标识 防止撞id
    public static final String PRE_FIX = UUID.randomUUID(true)+"-";

    public static DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //给lua脚本赋值
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"))  ;
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public  boolean tryLock(long timeOutSec) {
        //获取当前线程id
        String threadId = PRE_FIX + Thread.currentThread().getId();
        //通过redis获取锁
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_NAME + userName, threadId, timeOutSec, TimeUnit.SECONDS);
        //防止拆箱空指针
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {

        //通过lua脚本实现
        redisTemplate.execute(UNLOCK_SCRIPT, CollectionUtil.toList(KEY_NAME + userName)
                ,PRE_FIX + Thread.currentThread().getId());
//        //在删除锁之前 去判断是否是自己的锁
//        if(redisTemplate.opsForValue().get(KEY_NAME + userName).equals(PRE_FIX+Thread.currentThread().getId())) {
//            System.out.println("释放锁成功");
//            redisTemplate.delete(KEY_NAME + userName);
//        }
    }
}
