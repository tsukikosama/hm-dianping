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
    //??????????????????????????????redis
    @Test
    public void addSource(){
        //???????????????????????????
        List<Shop> list = service.list();
        //??????stream?????????????????????????????? ??????typeid??????
        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //??????id??????????????????????????????
        for(Map.Entry<Long, List<Shop>> entry : collect.entrySet()){
            //????????????id
            Long typeId = entry.getKey();

            //??????key
            String key = "shop:geo:"+typeId;
            //????????????shop??????
            List<Shop> value = entry.getValue();
            //?????????????????????????????????
            ArrayList<RedisGeoCommands.GeoLocation<String>> lists = new ArrayList<>(value.size());
            //c??????redis
            for(Shop shop : value){
                //????????????GeoLocation????????????????????????  ????????????????????????????????? ????????????
                RedisGeoCommands.GeoLocation geoLocation = new RedisGeoCommands.GeoLocation(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY()));
                //?????????????????????????????????
                lists.add(geoLocation);
            }

            //?????????redis??????
            template.opsForGeo().add(key,lists);
        }

    }
}
