package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 缓存工具类
 */
@Component
@Slf4j
public class RedisUtils {

    @Autowired
    private ShopServiceImpl service;

    @Autowired
    private StringRedisTemplate template;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //设置redis
    public void set(String key, Object value , Long time , TimeUnit unit){
        //把对象转成json字符串
        String jsonStr = JSONUtil.toJsonStr(value);
        template.opsForValue().set(key,jsonStr,time,unit);
    }

    //设置逻辑过期
    public void setWithLogicalExpire(String key, Object value , Long time , TimeUnit unit){
        //构建缓存过期对象
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //把对象转成字符串然后存入redis
        String jsonStr = JSONUtil.toJsonStr(redisData);
        template.opsForValue().set(key,jsonStr);
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        System.out.println(key);
        //1.判断redis中是否有商铺的缓存信息
        String shopCache = template.opsForValue().get(key);

        //2.如果没有就直接返回商铺信息
        if(StrUtil.isBlank(shopCache)){

            return null;
        }

        //把缓存实例化成对象
        RedisData data = JSONUtil.toBean(shopCache, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) data.getData(),type);
        //判断缓存是否过期
        if (data.getExpireTime().isAfter(LocalDateTime.now())){
            //如果时间是在现在之后 没有过期 直接返回
            return r;
        }
        //获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        //如果过期了去获取互斥锁
        if(getLock(lockKey)){
            //如果拿到了互斥锁 就开辟一个线程去查询商户信息 把新的内容存入缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询r对象
                    R newR = dbFallback.apply(id);
                    //再添加进缓存
                    Thread.sleep(200);
                    this.setWithLogicalExpire(key,newR,time,unit);
//                    service.findShopById(id, 20L);
                }catch (Exception e) {
                    throw new RuntimeException("登录失败");
                }
                    finally
                 {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //如果没获得到互斥锁就直接获取旧数据
        return r;
    }

    /**
     * 缓存穿透
     * @param keyPrefix  缓存前缀
     * @param id          缓存的id
     * @param type          缓存的类型
     * @param dbFallback    查询方法
     * @param time          过期时间
     * @param unit          时间类型
     * @param <R>           泛型
     * @param <ID>          泛型
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id ;
//        System.out.println(key);
        //1.判断redis中是否有商铺的缓存信息
        String shopCache = template.opsForValue().get(key);
//        System.out.println(shopCache);
        //2.如果有就直接返回商铺信息
        if(StrUtil.isNotBlank(shopCache)){
            R r = JSONUtil.toBean(shopCache, type);
            return r;
        }
        //如果缓存中的是空数据就直接返回空数据
        if(shopCache != null){
            return  null;
        }
        //3.如果没有就去数据库中查询
        //4.通过id查询数据库
        R r = dbFallback.apply(id);
        //5.判断是否查询到了商店信息
        if(BeanUtil.isEmpty(r)){
            //6.如果商铺信息不存在就返回一个一个加数据进入redis用来防止缓存穿透
            template.opsForValue().set(key,"",time,unit);
            return null;
        }
        String shoeC = JSONUtil.toJsonStr(r);
        //7.如果商铺信息不存在就存入redis中 方便下次访问直接从redis中获取
        template.opsForValue().set(key,shoeC,time,unit);
        //8.把商铺信息返回
        return r;
    }

    /**
     * 获取锁
     */
    public boolean getLock(String id){

        Boolean lock1 = template.opsForValue().setIfAbsent(id, "1",10, TimeUnit.SECONDS);
        return  BooleanUtil.isTrue(lock1);

    }

    /**
     * 释放锁
     */
    public void unLock(String id){
        template.delete(id);
    }
}
