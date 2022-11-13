# redis的用处

## 基于redis实现session共享问题

### 1 实现token共享

```java
@Override
    public Result login(LoginFormDTO phone, HttpSession session) {
        //0.校验手机号
        if(RegexUtils.isPhoneInvalid(phone.getPhone())){
            return Result.fail("手机号非法");
        }

        //1.校样验证码是否存在
        //Object msgCode = session.getAttribute("code");
        //c从token中获取
        String msgCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone.getPhone());
        System.out.println(msgCode);
        //2.判断手机号是否存在
        if(msgCode == null || !phone.getCode().equals(msgCode)){
            return Result.fail("验证码无效");
        }

        //3.判断用户是否存在
        User user = query().eq("phone", phone.getPhone()).one();

        //4.存在直接返回
        if(user == null){
            //5. 不存在 创建一个新的保存到数据库
           user =  createUser(phone.getPhone());

        }

        //返回一个token作为登录凭证
        String token = UUID.randomUUID().toString(true);

        UserDTO dto = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(dto,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((faildName,faildValue)->faildValue.toString()));
        //把对象存入rides
        redisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,stringObjectMap);
        //设置过期时间
        redisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //把user存入session
        //session.setAttribute("user",dto );
        //设置
        return Result.ok(token);
    }
```

把获取到的登录凭证存储到redis中，返回给前端一个登录凭证，然后就记得设计redis的过期时间要不然时间就了无效数据就会很多，redis是全部数据都存储在同一个地方，所以要对每个方法的redis存储一个属于自己的key以免服盖

### 2.通过访问路径刷新redis的持续时间

通过访问然后去重新刷新redis中的持续时间

这里有两个拦截器 refresh是用啦刷新redis的持续时间的，通过判断是否有携带token来判断 如果没有token就直接放行到login拦截器中，然后login判断线程中是否有登录的用户，如果没有就代表用户未登录直接拦截

如果refresh中有token 就通过token去redis中查询用户信息 ，

判断用户是否存在，如果存在然后返回一些基本信息，

然后保存到线程池中，刷新token的有效时间

```JAVA
 private StringRedisTemplate redisTemplate;

    public RefreashInterceptor(StringRedisTemplate r){
         this.redisTemplate = r;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断请求头中是否有token
        String token = request.getHeader("authorization");
//        System.out.println(token);
        //判断token是否存在 如果是401就放行 给下一个拦截器
        if(StrUtil.isBlank(token)){
//            response.setStatus(401);
            return true;
        }
        //获取session
        //Object user = request.getSession().getAttribute("user");
         //通过token去查用户
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(LOGIN_USER_KEY+token);

        //判断用户是否存在
        if (entries.isEmpty()){
            response.setStatus(401);
            return false;
        }
        //封装成一个对象
        UserDTO user =BeanUtil.fillBeanWithMap(entries,new UserDTO(),false);

        //存入线程池中
        UserHolder.saveUser(user);
        //刷新失效时间
        redisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);

        //销毁
        UserHolder.removeUser();
    }
```

```
@Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断请求头中是否有token
//        String token = UserHolder.getUser();
//        System.out.println(token);
        if(UserHolder.getUser() == null){
            response.setStatus(401);
           return false;
        }

        return true;
    }
```

### 3.配置拦截器



```java
 @Autowired
    private StringRedisTemplate template;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreashInterceptor(template)).addPathPatterns("/**").order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/shoe-type/**",
                        "/voucher/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);

    }
```

## 基于redis实现缓存功能



![AEC986FE249FF10C6FAE15C78D5247DB(https://github.com/tsukikosama/hm-dianping/tree/master/redis/AEC986FE249FF10C6FAE15C78D5247DB.jpg)//**访问的时候判断是否有缓存**  

 如果有缓存就直接获取 不需要查询数据库 ，如果没有缓存就去查询数据库 然后把查询到的数据添加进redis缓存中 方便下次访问

```java
@Autowired
    private StringRedisTemplate template;
    /**
     * 通过id查询shop信息b并进行缓存
     * @param id
     * @return
     */
    @Override
    public Result getShopById(Long id) {

        //1.判断redis中是否有商铺的缓存信息
        String shopCache = template.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.如果有就直接返回商铺信息
        if(StrUtil.isNotBlank(shopCache)){
            Shop shop1 = JSONUtil.toBean(shopCache, Shop.class);
            return Result.ok(shop1);
        }
        //3.如果没有就去数据库中查询
        //4.通过id查询数据库
        Shop shop = getById(id);


        //5.判断是否查询到了商店信息
        if(BeanUtil.isEmpty(shop)){
            //6.如果商铺信息不存在就返回一个404
            return Result.fail("用户不存在");
        }
        String shoeC = JSONUtil.toJsonStr(shop);
        //7.如果商铺信息存在就存入redis中 方便下次访问直接从redis中获取
        template.opsForValue().set(CACHE_SHOP_KEY + id,shoeC);
        //8.把商铺信息返回
        return Result.ok(shop);
    }
```

## 缓存穿透问题

![36D95146C86EB93F9F217D1DB833D376](G:\a_markdowm\redis\36D95146C86EB93F9F217D1DB833D376.jpg)

如果使用不存在的id访问事 然后缓存种没有 然后数据库种也查不到 然后就会出现缓存穿透问题，导致数据库宕机

可以使用布隆过滤和返回一个空对象来防止这个缓存穿透问题

布隆过滤器不用消耗额外的存储空间，判断数据库中有数据时，数据库中可能没有，不能百分百确定，

空对象 创建一个空对象反回，消耗额外的空间 但是百分百可以解决问题

通过实现返回空对象来解决缓存穿透问题



实现思路 就是去判断缓存中是否有数据 如果没有数据就去数据库中查询数据 如果没有查到就返回一个空值给页面。

```java
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
           return   BeanUtil.toBean(shopCache,Shop.class);

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
```

## 缓存击穿问题

缓存击穿问题是由于缓存在挂掉的时候有请求来访问，然后缓存中又没有对应的数据 然后查询数据库的时间比较久 ，有多个用户访问 然后就都会去查询数据库，然后导致数据库压力过大。

### 可以通过互斥锁来解决

可以使用互斥锁和逻辑过期来解决

互斥锁 就是在你访问到redis缓存中没有数据时候 然后去查询数据库之前 把锁拿走 如果这个时候有其他的用户来访问这个的时候 ，线程拿不到锁 就会去重试等待  可以设置一定时间后还没有成功就返回旧的书局，互斥锁可能会导致数据的不一致

```java
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

        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(lockId);
        }
        //8.把商铺信息返回
        return shop;

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
```

### 通过使用逻辑过期实现

![7A09F02EB0E4A27F71AE550307159A5F](G:\a_markdowm\redis\7A09F02EB0E4A27F71AE550307159A5F.jpg)

通过使用逻辑过期和互斥锁解决缓存击穿问题

 流程从redis中查缓存数据,判断是否查到了 如果没查到就直接返回一个空，如果查到了，就去判断缓存是否过期，如果过期了就获取互斥锁，然后判断互斥锁是否获取到了，如果获取到了就开一个线程，然后去查询数据库 然后i把查询到的信息写入redis 然后释放互斥锁，返回结果。 如果没有过期也是直接返回结果

//实现

使用逻辑过期时候，必须要有个东西存储信息，和一个过期时间 可以直接

```java
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
```



```java
//这是一个线程
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
```



##  通过使用工具类封装redis缓存

主要通过使用函数式变成，和泛型实现对redis工具的封装

## 通过使用redis实现全局共享唯一id

id一般使用uuid 或者 使用redis的时间戳+日期实现不用数据库的字段自动增加是为了提高安全性，避免暴露信息

通过封装成工具类 然后使用redis的自增长实现

```java
package com.hmdp.utils;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Date;

@Component
public class IdWorker {
//    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Long INITIAL_DATE = 1640995200L;

    private static final int NUMBER_BIT = 31;

    public IdWorker(StringRedisTemplate redisTemplate){
        this.redisTemplate = redisTemplate;
    }
    /**
     * 获取订单id
     * @param prefix
     * @return
     */
    public Long nextId(String prefix){
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
        long increment = redisTemplate.opsForValue().increment("irc"+":"+prefix + ":" + date );
        //返回订单号
        return  date << NUMBER_BIT | increment ;
    }

}

```

## 解决多线程线程安全问题

在多线程中事务可能会出现线程安全问题，所以在高并发的情况下，我们必须通过锁来保证线程安全问题

有两个锁可以来实现这个线程安全 

悲观锁：默认所有的线程都会出现线程安全问题，直接给线程都上锁，安全性高不会出错但是性能比较低。

乐观锁：不给线程上锁，默让出现线程安全的几率比较低，在修改数据库的时候去对比数据库中的版本(在数据库中添加一个version字段 每次更新的时候都去改变这个版本 用来保证线程安全问题)是否一直，如果版本不一致就回归事物，如果一直就更新事物。

 ![img](file:///C:\Users\10833\Documents\Tencent Files\1083344129\Image\C2C\9240F6F655A8D8BA3457593BA08CB30A.jpg) 

```java
public Result seckillVoucher(Long voucherId) {
        //查询消费券信息
        SeckillVoucher seckillVoucher = voucherService.getById(voucherId);

        //判断秒杀是否开始 未开始返回失败
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断是否结束 结束直接返回失败
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀结束");
        }
        //判断库存是否充
        //不充足就返回失败
        if (seckillVoucher.getStock() < 1){
            return Result.fail("库存不住");
        }
            //使用乐观锁来解决线程安全问题 如果库存大于0 才能实现
            //充足就扣减库存
            boolean flag = voucherService.update().setSql("stock = stock -1")
                    .eq("voucher_id", voucherId).gt("stock",0)
                    .update();

        if (!flag){
            return Result.fail("库存不足");
        }

        VoucherOrder order = new VoucherOrder();
        //设置用户id
        //测试数据
//        order.setUserId(RandomUtil.randomLong(152035));
        order.setUserId(UserHolder.getUser().getId());
        Long orderId = idWorker.nextId("order");
        //设置订单id
         order.setId(orderId);
        //设置代金券id
        order.setVoucherId(voucherId);
        order.setCreateTime(LocalDateTime.now());
        //创建订单
        voucherOrder.save(order);
        //返回id
        return Result.ok(orderId);
    }
```

这里因为库存每次都会变动 可以用来代替乐观锁的作用，只要库存不大于0就成功。

## 使用悲观锁来解决一人一单问题

## 解决集群线程安全问题

如果服务部署在集群上，每个tomcat都有属于自己的线程 各自的锁互不影响，所以会出现线程问题使用redis的 **SETNX**  数据类型来实现加锁。每个用户都又自己的锁 如果锁不在了 还有线程访问就会进行等待。

步骤



创建一个ILock的接口

```java

public interface ILock {

    boolean tryLock(long timeOutSec);

    void unLock();
}

```

去继承实现这个接口

```java
public  class SimpleRedisLock implements ILock{

    public StringRedisTemplate redisTemplate;
    public String userName;

    public SimpleRedisLock(StringRedisTemplate redisTemplate, String Name) {
        this.redisTemplate = redisTemplate;
        this.userName = Name;
    }

    public static final String KEY_NAME = "lock:";

    @Override
    public  boolean tryLock(long timeOutSec) {
        //获取当前线程id
        long threadId = Thread.currentThread().getId();
        //通过redis获取锁
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_NAME + userName, threadId + "", timeOutSec, TimeUnit.SECONDS);
        //防止拆箱空指针
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        redisTemplate.delete(KEY_NAME + userName);
    }
}

```

然后去判断获取锁

```java
public Result seckillVoucher(Long voucherId) {
        //查询消费券信息
        SeckillVoucher seckillVoucher = voucherService.getById(voucherId);

        //判断秒杀是否开始 未开始返回失败
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断是否结束 结束直接返回失败
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀结束");
        }
        //判断库存是否充
        //不充足就返回失败
        if (seckillVoucher.getStock() < 1){
            return Result.fail("库存不住");
        }
        //
        Long userId = UserHolder.getUser().getId();
        //synchronized(要锁的对象) intern方法去产量池中找值一样的  要不然每个每个线程中每个对象的值都不一样
//        synchronized(userId.toString().intern()) {
        //尝试获取锁

            SimpleRedisLock simpleRedisLock = new SimpleRedisLock(template, "Seckill");
            boolean b = simpleRedisLock.tryLock(1200);
            //判断是否获取刀了
            if (!b) {
                return Result.fail("静止重复下单");
            }
        try {
            //获取代理对象
            ISeckillVoucherService proxy = (ISeckillVoucherService) AopContext.currentProxy();
            return proxy.SoleSale(voucherId);
        }finally {
            simpleRedisLock.unLock();
        }
//        }
    }
    @Transactional
    @Override
    public Result SoleSale(Long voucherId){
        VoucherOrder order = new VoucherOrder();
        //设置用户id
        //测试数据
//        order.setUserId(RandomUtil.randomLong(152035));
        Long userId = UserHolder.getUser().getId();
        order.setUserId(userId);
        Long orderId = idWorker.nextId("order");
        //判断用户是否下单了
        int count = voucherOrder.query().eq("user_id", UserHolder.getUser()
                .getId()).eq("voucher_id", voucherId).count();
        //如果用户下单了
        if(count > 0){
            return Result.fail("请勿重复下单");
        }
        //使用乐观锁来解决线程安全问题 如果库存大于0 才能实现
        //充足就扣减库存
        boolean flag = voucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();

        if (!flag){
            return Result.fail("库存不足");
        }
        //设置订单id
        order.setId(orderId);

        //设置代金券id
        order.setVoucherId(voucherId);
        order.setCreateTime(LocalDateTime.now());
        //创建订单
        voucherOrder.save(order);
        //返回id
        return Result.ok(orderId);
    }
```

## 通过lua脚本来集群释放锁问题

![](G:\a_markdowm\redis\4DF9846E3906AEEED03EB695E20040C1.jpg)

这里产生线程安全的愿意就是阻塞太久导致锁失效 然后其他的线程来获取锁了 

```lua
-- 通过lua脚本实现锁的释放
-- redis.call redis的命令  keys 用来存key的  argv 用来存其他的参数的
if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0
```

```java
 public static DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //给lua脚本赋值
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"))  ;
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
```

通过lua脚本的原子性 可以解决这一问题

## Redission

## redission的使用

坐标

```java
<dependency>
	<groupId>org.redisson</groupId>   		 		  		<artifactId>redisson</artifactId>  	                   <version>3.13.6</version> 
	</dependency>

```

添加redisson的客户端

```java
@Configuration
public class RedisConfig {    @Bean    public RedissonClient redissonClient() {
    // 配置类       
    Config config = new Config();  
    // 添加redis地址，这里添加了单点的地址，也可以使用    
    config.useClusterServers()
    添加集群地址         config.useSingleServer().setAddress("redis://192.168.150.101:6379").setPassowrd("123321");      
    // 创建客户端        
    return Redisson.create(config);    }
                                    }

```

![9EA058DBCCC50E3D18F92FCA80D3A0D4](G:\a_markdowm\redis\9EA058DBCCC50E3D18F92FCA80D3A0D4.jpg)



使用redission来解决redis的线程安全问题

### 锁的不可重入

 不可重入就是同一个线程不能多次获取同一把锁，考研使用redission来解决此问题 

redission 使用 redis种hash结果来实现锁的可重入，实现步骤就是判断是否是同一线程获取这把锁 如果是就让获取的次数加一，不是就获取失败，通过判次数是否为0来判断锁是否被完全释放了，因为锁的使用次数和释放次数应该保持一致。

### 不可重试问题。

redission 通过使用watchdog(看门狗机制来实现) 你传递参数就会启用看门口狗机制来不断的刷新redis的持续时间

### 超时释放

每次访问的时候都会去获取锁判断是否获取到了 如果没有获取到，就去判断刚刚获取锁消耗了多久时间，然后和超时时间对比看看是否过期了，如果没有过期就去等待锁释放的信号，如果获取到了这个信号然后就去尝试重新获取锁，获取锁之前还需要对时间进行判断看看是否超时了，如果没有超时就循环继续重试。(使用信号的方式不用一直尝试 导致消耗cpu资源)

### mutilock连锁机制

redission可以使用连锁机制解决redis宕机导致的锁失效问题，实现的思路就是启用多个redis分布式锁 然后获取锁的时候要对所有的节点都获取锁 ，只有全部获取成功了，才算获取锁成功，mutilock是使用迭代器来获取锁的，如果锁获取失败就会把前面获取的锁清空。也有看门狗机制。

## 秒杀优化---异步秒杀

之前的秒杀 查询功能都是全部在mysql上运行的所以速度比较慢，现在把查询和判断 放在redis上进行优化 然后进行异步处理下单。

#### 1.使用lua脚本获取查询信息

 使用redis中的set集合存入商品数量和购买的用户来判断用户是否可以下单，然后通过lua脚本去更新redis中的数据，异步请求去处理订单的支付问题

//在添加秒杀全的时候 往redis中存一份

```java
 @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保持优惠券进redis 通过redis判断是否下单
        redisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(), voucher.getStock().toString());
    }
```

//lua脚本的编写

```lua
---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by 10833.
--- DateTime: 2022/10/31 20:53
----- redis.call redis的命令  keys 用来存key的  argv 用来存其他的参数的
---
--接收用户的id
local userId = ARGV[1];
--接收秒杀券的id
local seckill = ARGV[2];
--库存key
local stockKey = 'seckill:stock:' .. seckill;
--订单key
local orderKey = 'seckill:order:' .. seckill;

--判断库存是否从充足
if(tonumber(redis.call('get',stockKey)) <=0)then
    return 1;
end
--判断用户是否下档
if (redis.call('sismember',orderKey ,userId) == 1) then
    return 2;
end
--可以下单
--扣减库存
redis.call('incrby',stockKey,-1)
--将用户存入优惠券集合
redis.call('sadd',orderKey,userId);
return 0;


```

//实现秒杀全的逻辑

```java
  //获取lua脚本
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //给lua脚本赋值
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /**
     * 通过结合redis优化秒杀效率
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取userId信息
        Long userId = UserHolder.getUser().getId();
        //1.运行lua脚本
        Long result = template.execute(SECKILL_SCRIPT, Collections.emptyList(),
                 userId.toString(),voucherId.toString()
        );
        int r = result.intValue();
        //2.判断结果是否为0
        if(r != 0){
            //2.1不为0  代表没有购买资格
            //判断是1 还是 2
            return Result.fail(r == 0?"库存不足":"重复下单");
        }


        //2.2 为0 有购买资格，把下单信息保存到阻塞队列
        //TODO 添加到阻塞队列种
         //TODO 添加到阻塞队列种
        orderQueue.add(order);

        //获取订单id
        Long orderId = redisIdWorker.nextId("order");
        //返回订单id
        return Result.ok(orderId);
    }

```

//通过异步命令来保存订单

```java
 //创建一个阻塞队列 阻塞队列 有数据的时候才会运行
    private BlockingQueue<VoucherOrder> orderQueue = new ArrayBlockingQueue(1024 * 1024);
    //创建一个线程池
    private static final ExecutorService SECKILL_ORDER_EXECTOR = Executors.newSingleThreadExecutor();
    //当springBoot 启动的时候就会运行这个阻塞
    //使用这注解就会在springboot启动的时候自动初始化里面的neir
    @PostConstruct
    public  void init(){
        SECKILL_ORDER_EXECTOR.submit(new VoucherOrderHanlder());
    }

    //通过线程池去执行任务 这里使用内部类的 方法 自己定义实现一个外部类也可以实现该效果
    private class VoucherOrderHanlder implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    log.info("异步");
                    //获取阻塞队列中的元素
                    VoucherOrder voucherOrder = orderQueue.take();
                    //获取订单
                    HandleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
//                    e.printStackTrace();
                    log.error("下单异常");
                }


            }
        }
    }

    /**
     * 保存订单信息
     * @param voucherOrder
     */
    private IVoucherOrderService proxy;
    private void HandleVoucherOrder(VoucherOrder voucherOrder) {
        //获取锁
        RLock order1 = redissionClient.getLock("lock:order:"+voucherOrder.getUserId());
        boolean lock = order1.tryLock();
        //判断锁是否获取成功
        if(!lock){
            log.error("不允许重复下单");
            return;
        }
        try {

            proxy.SoleSale(voucherOrder);
        }finally {
            order1.unlock();
        }
        //设置订单id
        log.info("创建订单信息");
        //创建订单
        save(voucherOrder);
    }
```

 ![img](file:///C:\Users\10833\Documents\Tencent Files\1083344129\Image\C2C\1C5205F87CCE0509D908172ED9B2A3D9.jpg) 

### 2.基于stream消息队列实现异步秒杀功能

通过利用stream的消息处理功能来实现异步秒杀功能，流程就是stream消息队列获取到消息就进行处理，如果在处理的过程中出现了宕机失败，就把消息存入pending-list这个队列，然后下次继续从pending-list中处理消息

tips(不要出现两次保存订单 要不然会造成死循环 消息一直处理不了)

![](G:\a_markdowm\redis\78BD58EABA6B400B18E1536E3FCCAF9F.jpg)

```lua
---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by 10833.
--- DateTime: 2022/10/31 20:53
----- redis.call redis的命令  keys 用来存key的  argv 用来存其他的参数的
---
--接收用户的id
local userId = ARGV[1];
--接收秒杀券的id
local seckill = ARGV[2];
---接收的orderid
local orderId = ARGV[3]
--库存key
local stockKey = 'seckill:stock:' .. seckill;
--订单key
local orderKey = 'seckill:order:' .. seckill;

--判断库存是否从充足
if(tonumber(redis.call('get',stockKey)) <=0)then
    return 1;
end
--判断用户是否下档
if (redis.call('sismember',orderKey ,userId) == 1) then
    return 2;
end
--可以下单
--扣减库存
redis.call('incrby',stockKey,-1)
--将用户存入优惠券集合
redis.call('sadd',orderKey,userId);
--添加一个消息队列组   第一个是创建stream组的指令  第二个设置组的名字  * 表示消息的名字由redis自己创建  后秒的表示消息的记录
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',seckill,'id',orderId);
return 0;

```

```java
/**
     * 通过结合redis优化秒杀效率 通过xgroup实现
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {

        //获取userId信息
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisIdWorker.nextId("order");
        //1.运行lua脚本
        Long result = template.execute(SECKILL_SCRIPT, Collections.emptyList(),
                userId.toString(),voucherId.toString(),String.valueOf(orderId)
        );
        int r = result.intValue();
        //2.判断结果是否为0
        if(r != 0){
            //2.1不为0  代表没有购买资格
            //判断是1 还是 2
            return Result.fail(r == 0?"库存不足":"重复下单");
        }
        proxy =(IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

@PostConstruct
    public  void init(){
        SECKILL_ORDER_EXECTOR.submit(new VoucherOrderHanlder());
    }
    //通过线程池去执行任务 这里使用内部类的 方法 自己定义实现一个外部类也可以实现该效果 配合xgroup使用的
    private class VoucherOrderHanlder implements Runnable{
        private static final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {

//                    System.out.println(1);
                    //获取消息队列中的订单信息  第一给参数 组的名字和 消息的名字   第二个参数一次要读取几条消息  第三个参数要读取那个队列中的消息
                    List<MapRecord<String, Object, Object>> orderMsgs = template.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
//                    System.out.println(2);
                    //2. 判断消息是否获取成功
                    if (orderMsgs == null || orderMsgs.isEmpty()) {
                        //2.1如果获取失败说明没有队列信息 继续
                        continue;
                    }

//                    System.out.println(3);
                    //
                    //3.如果获取成功就可以下单
                    //获取到订单中的信息
                    MapRecord<String, Object, Object> order = orderMsgs.get(0);
                    Map<Object, Object> value = order.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder()
                            , true);
//                获取订单 可以下单
//                    System.out.println(4);
//                    System.out.println(voucherOrder);
                    HandleVoucherOrder(voucherOrder);
//                    System.out.println(5);
                    //ack确认 sack stream.order g1 c1
                    template.opsForStream().acknowledge(queueName, "g1", order.getId());
                }catch (Exception e){
                    log.error("处理pending-list失败",e);
                    handlePendingList();
                }
            }
        }
        //看pading中是否有消息 如果有消息就把他消费了
        private void handlePendingList() {
            while (true){
                try {
                    //1.获取pending-list中的订单 Xreadgroup group g1 c1 count 1 streams stream.order 0
                    List<MapRecord<String, Object, Object>> orderMsgs = template.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (orderMsgs == null || orderMsgs.isEmpty()) {
                        //2.1如果获取失败说明没有pening-list信息 继续
                        break;
                    }
                    // 解析数据
                    //3.如果获取成功就可以下单
                    //获取到订单中的信息
                    MapRecord<String, Object, Object> order = orderMsgs.get(0);
                    Map<Object, Object> value = order.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder()
                            , true);
                    // 获取订单

                    HandleVoucherOrder(voucherOrder);
                    //ack确认
                    template.opsForStream().acknowledge(queueName, "g1", order.getId());
                }catch (Exception e){
                    log.error("订单处理失败",e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
//
                }
            }
        }
    }
```

## 实现点赞功能（sortedSet)

实现步骤通过sortedSet来进行排序 

判断当前id是否有点赞过就是通过id去查询看看能不能看到分，如果有就是点赞过了就把数据库中的点赞数减一 然后把redis中的数据移除，如果没有就把数据库中的点赞数+1，添加进redis 如果sortedSet可以来排序点赞顺序

```java
 /**
     * 通过sortset进行点赞的顺序yichu
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //获取user对象
        Long userId = UserHolder.getUser().getId();

        String key = BLOG_LIKED_KEY + id;
        //判断是否是set集合的对象
        Double score = template.opsForZSet().score(key, userId.toString());
//        System.out.println(success);
        //如果不是就可以进行点赞
        if(score == null){
            //数据库+1
            boolean isSuccees = update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccees){
                //把博客的id单座key  用户的id当作值存入redis中
                template.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            //如果是就可以进行点赞
        }else{
            //数据库-1
            boolean isSuccees = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccees){
                ////把ridis中的的数据移除
                System.out.println(key);
                template.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }
```

## 实现关注功能



```java
@Override
    public Result follow(Long id, boolean isfollow) {
        //获取用户信息
        Long userId = UserHolder.getUser().getId();
        //判断是否关注了 关注了就是取消关注 没关注就是关注
        if(isfollow){
            //已经关注了
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",id));
        }else{
            //没有关注
            //添加一个关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            save(follow);
        }
        return Result.ok();
    }
```

### 判断是否关注

```java
 @Override
    public Result isFollow(Long id) {
        //获取用户信息
        Long userId = UserHolder.getUser().getId();
        //判断是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }
```

## 共同关注功能

通过redis中的set集合获取交际的功能实现，在关注时，把关注的id添加进redis中，到时候使用intersect方法集合获取相同的元素

共同关注的代码

```java
@Override
    public Result commonFollow(Long id) {

        //获取当前登录的user的id
        String uid = FOLLOW_KEY +  UserHolder.getUser().getId();
        //判断是否有交集
        Set<String> intersect = template.opsForSet().intersect(uid, FOLLOW_KEY + id);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //把共同关注的用户集合转成long
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //通过id去查询共同关注的用户 然后转成dto对象
        List<UserDTO> dto = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        return Result.ok(dto);
    }
```

取关关注代码的改造

```java
@Override
    public Result follow(Long id, boolean isfollow) {

        System.out.println(isfollow);
        //获取用户信息
        Long userId = UserHolder.getUser().getId();
        //共同关注的key
        String followKey =  FOLLOW_KEY  +  userId;
        //判断是否关注了 关注了就是取消关注 没关注就是关注
        if(!isfollow){
            log.info("取关");
            //已经关注了
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            //判断是否移除成功了 移除成功从redis中的关注id移除
            if(remove){
                template.opsForSet().remove(followKey,id.toString());
            }

        }else{
            log.info("关注");
            //没有关注
            //添加一个关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean save = save(follow);
            //判断是否保存成功 保存把当前关注的id存入redis
            if(save){
                template.opsForSet().add(followKey,id.toString());
            }

        }
        return Result.ok();
    }
```

#### 实现附近的功能(geo)

先把店铺的坐标数据存入redis中  redis有hash算法会把经纬度转成一个哈希值，然后通过哈希值去比较距离。

#####  添加进redis

把店铺数据读取出来 然后一次性添加

```java
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
```

##### 从redis中读取店铺信息然后判断是否在范围内

```java
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
```

### 实现每日签到功能(bitMap)

```java
 @Override
    public Result sign() {
        //获取当前用户
        Long uid = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        //拼接签到key
        String key = USER_SIGN_KEY + uid + suffix ;
        //获取当前是第几天
        int nowDay = now.getDayOfMonth();
        //签到
        redisTemplate.opsForValue().setBit(key,nowDay-1,true);
        return Result.ok();
    }
```

实现统计当月签到数功能(使用与运算)

```java
  /**
     * 统计本月连续签到次数
     * @return
     */
    @Override
    public Result signCount() {
        //获取当前用户
        Long uid = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        //拼接签到key
        String key = USER_SIGN_KEY + uid + suffix ;
        //获取当前是第几天
        int nowDay = now.getDayOfMonth();
        //获取本月签到的次数
        List<Long> list = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get
                        (BitFieldSubCommands.BitFieldType.unsigned(nowDay)).valueAt(0));
        //判断是否获取到了
        if(list.isEmpty() || list == null){
            return Result.ok(0);
        }
        //如果获取到了就取出第一个 取出的是一个十进制的数 对他和1进行与运算
        Long num = list.get(0);
        if(num == 0 || num==null){
            return Result.ok(0);
        }
        int count = 0 ;
        //循环与运算 然后位移判断是否签到
        while (true){
            //与1 是 0 就是没签到直接退出循环
            if( (num & 1) == 0){
                break;
            }else{
                count++;
            }
            //循环之后把数字右移
            num >>>= 1;
        }
        return Result.ok(count);
    }
```

## uv统计(hyperloglog)

hyperloglog是一个去重的数据类型 用来实现uv统计的 数据会有一些小误差，内存控制在15kb之内

```java
@Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
```

