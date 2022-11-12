package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate template;

    @Autowired
    private RedisUtils redisUtils;
    /**
     * 通过id查询shop信息b并进行缓存
     * @param id
     * @return
     */
    @Override
    public Result getShopById(Long id) {

        //缓存穿透
        //Shop shop = getShop(id);

        //互斥锁解决缓存击穿
//        Shop shop = redisUtils.queryWithPassThrough("cache:shop:",id,Shop.class, id2 -> getById(id2)
//                , 10L,TimeUnit.SECONDS);
        //逻辑过期解决缓存击穿
        Shop shop =  redisUtils.queryWithLogicalExpire
                (CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
        System.out.println(shop);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop expire(Long id){
         String key = CACHE_SHOP_KEY + id;
        //1.判断redis中是否有商铺的缓存信息
        String shopCache = template.opsForValue().get(key);
        //2.如果没有就直接返回商铺信息
        if(StrUtil.isBlank(shopCache)){
//            Shop shop1 = JSONUtil.toBean(shopCache, Shop.class);
//            System.out.println("test"+shop);
            return null;
        }

        //把缓存实例化成对象
        RedisData data = JSONUtil.toBean(shopCache, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) data.getData(),Shop.class);
        //判断缓存是否过期
        if (data.getExpireTime().isAfter(LocalDateTime.now())){
            //如果时间是在现在之后 没有过期 直接返回
            return shop;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        //如果过期了去获取互斥锁

        if(getLock(lockKey)){

                //如果拿到了互斥锁 就开辟一个线程去查询商户信息 把新的内容存入缓存
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        this.findShopById(id, 20L);
                    }catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        //释放锁
                        unLock(lockKey);
                    }
                });
            }
        //如果没获得到互斥锁就直接获取旧数据
        return shop;
    }
    /**
     * 互斥锁
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        //1.判断redis中是否有商铺的缓存信息
        String shopCache = template.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.如果有就直接返回商铺信息
        if(StrUtil.isNotBlank(shopCache)){
            Shop shop1 = JSONUtil.toBean(shopCache, Shop.class);
            return shop1;
        }
        //如果不为空 也不是null
        //如果缓存中的是空数据就直接返回空数据
        if(shopCache != null){
            return null;
        }
        Shop shop = null;
        String lockId = LOCK_SHOP_KEY + id;
        try {
            //获取到了锁 就去数据库中查询
            //获取锁
            boolean lock = getLock(lockId);
            //判断是否获取到了锁
            if(!lock){
                //没有获取到锁就休眠一段时间
                Thread.sleep(100);
                //然后重新获取
                return  queryWithMutex(id);
            }
            //3.如果没有就去数据库中查询
            //4.通过id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);

            //5.判断是否查询到了商店信息
            if(BeanUtil.isEmpty(shop)){
                //6.如果商铺信息不存在就返回一个一个加数据进入redis用来防止缓存穿透
                template.opsForValue().set(CACHE_SHOP_KEY + id,"");
                return null;
            }
            String shoeC = JSONUtil.toJsonStr(shop);
            //7.如果商铺信息存在就存入redis中 方便下次访问直接从redis中获取
            template.opsForValue().set(CACHE_SHOP_KEY + id,shoeC);

        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(lockId);
        }
        //8.把商铺信息返回
        return shop;

    }
    /**
     * 根据id查询数据库
     *
     */
    public void findShopById(Long id,Long time) throws InterruptedException {
        //数据库查询shop数据
        Shop shop = getById(id);

        Thread.sleep(200);
        //然后构建shopDate数据
        RedisData data = new RedisData();
        //设置值
        data.setData(shop);
        //设置逻辑过期时间
        data.setExpireTime(LocalDateTime.now().plusSeconds(time));

        System.out.println(data.toString());
        //变json字符串
        String s = JSONUtil.toJsonStr(data);
        //设置缓存
        template.opsForValue().set(CACHE_SHOP_KEY + id,s);

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
    /**
     * 解决缓存穿透问题
     * @param id
     * @return
     */
    public Shop getShop(Long id){
        //1.判断redis中是否有商铺的缓存信息
        String shopCache = template.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.如果有就直接返回商铺信息
        if(StrUtil.isNotBlank(shopCache)){
            Shop shop1 = JSONUtil.toBean(shopCache, Shop.class);
            return shop1;
        }

        //如果缓存中的是空数据就直接返回空数据
        if(shopCache != null){
           return   null;

        }
        //3.如果没有就去数据库中查询
        //4.通过id查询数据库
        Shop shop = getById(id);


        //5.判断是否查询到了商店信息
        if(BeanUtil.isEmpty(shop)){
            //6.如果商铺信息不存在就返回一个一个加数据进入redis用来防止缓存穿透
            template.opsForValue().set(CACHE_SHOP_KEY + id,"");
            return null;
        }
        String shoeC = JSONUtil.toJsonStr(shop);
        //7.如果商铺信息存在就存入redis中 方便下次访问直接从redis中获取
        template.opsForValue().set(CACHE_SHOP_KEY + id,shoeC);
        //8.把商铺信息返回
        return shop;
    }
    @Override
    public Result updateShopById(Shop shop) {
        //判断店铺是否存在
        Long id = shop.getId();

        //判断id是否存在
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //关系数据库中的数据
        updateById(shop);
        //把缓存中的旧数据删除
        template.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopBype(Integer typeId, Integer current, Double x, Double y) {
        //判断是否是 距离搜索
        if(x == null || y ==null){
            //没有x y 就是普通的查询直接返回即可
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page);
        }
        //获取分页的数据
        int start = (current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //获取店铺的类型key
        String key = SHOP_GEO_KEY + typeId ;
        //通过店铺id去redis中查询商铺信息 这里获取的是从零开始到结尾的店铺数据选哟自己去截取
        GeoResults<RedisGeoCommands.GeoLocation<String>> result = template.opsForGeo().search(key,
                GeoReference.fromCoordinate(new Point(x, y)), new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //没获取到店铺数据就返回空
        if(result == null){
            return Result.ok(Collections.emptyList());
        }

        //获取全部的商铺数据
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = result.getContent();
        //存取店铺id集合
        ArrayList<Long> shopIds = new ArrayList<>(content.size());
        System.out.println(content);
        //距离的集合
        Map<String,Distance> distanceMap = new HashMap<>(content.size());
        System.out.println(start);
        //通过stream流获取想要的页数 遍历去处理想要的数据
        content.stream().skip(start).forEach(
                item -> {
                    //获取每个对象的id然后存入集合中
                    String shipId = item.getContent().getName();
                    //把商铺的id 存入 集合
                    shopIds.add(Long.valueOf(shipId));
                    //把距离存入集合中
                    distanceMap.put(shipId,item.getDistance());
                }
        );

        String ids = StrUtil.join(",",shopIds);
        System.out.println(ids);
        //通过id去查询店铺信息
        List<Shop> shop = query().in("id", shopIds).last("order by field(id," + ids+")").list();
        //遍历shop集合设置距离信息
        System.out.println(shop.toString());
        for(Shop item : shop){
            item.setDistance(distanceMap.get(item.getId().toString()).getValue());
        }
        return Result.ok(shop);
    }
}
