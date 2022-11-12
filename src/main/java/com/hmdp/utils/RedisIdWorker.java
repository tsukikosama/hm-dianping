package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
//    @Autowired
    public  StringRedisTemplate redisTemplate;

    private static final Long INITIAL_DATE = 1640995200L;

    private static final int NUMBER_BIT = 32;

    public RedisIdWorker(StringRedisTemplate redisTemplate){
        this.redisTemplate = redisTemplate;
    }
    /**
     * 获取订单id
     * @param prefix
     * @return
     */
    public  Long nextId(String prefix){
        //获取当前的世界
        LocalDateTime now = LocalDateTime.now();
        //获取当前时间
        Long dateZone = now.toEpochSecond(ZoneOffset.UTC);
        //获取时间差
        long date = dateZone - INITIAL_DATE;

        //获取当前日期
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
//        System.out.println(format);
//        System.out.println(redisTemplate);
        //存入redis 自增长
        long increment = redisTemplate.opsForValue().increment("irc"+":"+prefix + ":" + format );
        //返回订单号
        return  date << NUMBER_BIT | increment ;
    }

}
