package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPLIST_KEY;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;


    @Autowired
    private StringRedisTemplate redisTemplate;
    @GetMapping("list")
    public Result queryTypeList() {
        //第一次访问判断redis中是否有缓存数据
        String shopeList = redisTemplate.opsForValue().get(CACHE_SHOPLIST_KEY);
        //如果缓存中有数据 就从缓存中获取数据
        if(StrUtil.isNotBlank(shopeList)){
            //把数据转成对象 然后返回给前端
            List<ShopType> shopTypes = JSONUtil.toList(shopeList, ShopType.class);
            return Result.ok(shopTypes);
        }
        //缓存中没有数据 通过数据库去查询
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        //判断是否查询到了
        if(BeanUtil.isEmpty(typeList)){
            return Result.ok("数据有误");
        }
        //把数据变成json字符串
        String s = JSONUtil.toJsonStr(typeList);

        //有数据 把数据存入redis中方便下次访问 并且设置一个过期时间 方便后面缓存同步
        redisTemplate.opsForValue().set(CACHE_SHOPLIST_KEY,s,30L, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }

}
