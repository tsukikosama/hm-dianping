package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RedisUtils;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private  ShopServiceImpl service;

    @Autowired
    private RedisUtils redisUtils;

    ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Autowired
    private RedisIdWorker worker;
    @Autowired
    private StringRedisTemplate template;
    @Test
    public void idWord() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
          for (int i = 0  ; i < 300 ; i++){
              Long orderId = worker.nextId("order");
              System.out.println(orderId);
          }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i = 0 ; i < 300 ;i++){
            executorService.submit(task);
        }

        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println(end - begin);
    }

    @Test
    public void add() throws InterruptedException {
        service.findShopById(1L,10L);
    }
    @Test
    public void addShope()  {
        Shop shop = service.getById(1);
        String s = JSONUtil.toJsonStr(shop);
        redisUtils.queryWithLogicalExpire("cache:shop:",shop, Shop.class, id2 -> service.getById(id2)
                , 10L,TimeUnit.SECONDS);

    }
    //把数据库中的数据存入redis
    @Test
    public void addSource(){
        //从数据空中获取数据
        List<Shop> list = service.list();
        //通过stream留对店铺数据进行分组 通过typeid分组
        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //通过id去添加不同类型的商铺
        for(Map.Entry<Long, List<Shop>> entry : collect.entrySet()){
            //获取商户id
            Long typeId = entry.getKey();

            //添加key
            String key = "shop:geo:"+typeId;
            //每个都是shop对象
            List<Shop> value = entry.getValue();
            //创建一个存储位置的集合
            ArrayList<RedisGeoCommands.GeoLocation<String>> lists = new ArrayList<>(value.size());
            //c存入redis
            for(Shop shop : value){
                //创建每个GeoLocation对象然后添加进去  也可以一个一个添加进去 效率很低
                RedisGeoCommands.GeoLocation geoLocation = new RedisGeoCommands.GeoLocation(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY()));
                //把每个对象都存入集合中
                lists.add(geoLocation);
            }

            //添加进redis集合
            template.opsForGeo().add(key,lists);
        }

    }
}
