package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService voucherService;
    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private RedissonClient redissionClient;
    @Autowired
    private StringRedisTemplate template;
    @Autowired
    private RedisIdWorker redisIdWorker;


    //获取lua脚本
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //给lua脚本赋值
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
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

//    //通过线程池去执行任务 这里使用内部类的 方法 自己定义实现一个外部类也可以实现该效果 配合组设队列使用的
//    private class VoucherOrderHanlder implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    log.info("异步");
//                    //获取阻塞队列中的元素
//                    VoucherOrder voucherOrder = orderQueue.take();
//                    //获取订单
//                    HandleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
////                    e.printStackTrace();
//                    log.error("下单异常");
//                }
//
//
//            }
//        }
//    }

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
            System.out.println(111);
            proxy.SoleSale(voucherOrder);
        }finally {
            order1.unlock();
        }
        //设置订单id
        log.info("创建订单信息");
        System.out.println(voucherOrder.toString());
        //创建订单
//        boolean save = save(voucherOrder);
//        System.out.println(save);
    }
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
//    /**
//     * 通过结合redis优化秒杀效率  通过阻塞队列实现
//     * @param voucherId
//     * @return
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取userId信息
//        Long userId = UserHolder.getUser().getId();
//        //1.运行lua脚本
//        Long result = template.execute(SECKILL_SCRIPT, Collections.emptyList(),
//                userId.toString(),voucherId.toString()
//        );
//        int r = result.intValue();
//        //2.判断结果是否为0
//        if(r != 0){
//            //2.1不为0  代表没有购买资格
//            //判断是1 还是 2
//            return Result.fail(r == 0?"库存不足":"重复下单");
//        }
//
//
//        //2.2 为0 有购买资格，把下单信息保存到阻塞队列
//        VoucherOrder order = new VoucherOrder();
//        //获取订单id
//        long orderId = redisIdWorker.nextId("order");
//        //设置订单id
//        order.setId(orderId);
//        //设置代金券id
//        order.setVoucherId(voucherId);
//        order.setUserId(userId);
////        order.setCreateTime(LocalDateTime.now());
//        //TODO 添加到阻塞队列种
//        orderQueue.add(order);
//
//        //实现
//        proxy =(IVoucherOrderService) AopContext.currentProxy();
//        //返回订单id
//        return Result.ok(orderId);
//    }

    /**
     * 使用最初的版本实现秒杀券一人一单功能
     //     * @param voucherId
     * @return
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询消费券信息
//        SeckillVoucher seckillVoucher = voucherService.getById(voucherId);
//
//        //判断秒杀是否开始 未开始返回失败
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断是否结束 结束直接返回失败
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀结束");
//        }
//        //判断库存是否充
//        //不充足就返回失败
//        if (seckillVoucher.getStock() < 1){
//            return Result.fail("库存不住");
//        }
//        //
//        Long userId = UserHolder.getUser().getId();
//        //synchronized(要锁的对象) intern方法去产量池中找值一样的  要不然每个每个线程中每个对象的值都不一样
////        synchronized(userId.toString().intern()) {
//        //尝试获取锁
//
//            SimpleRedisLock simpleRedisLock = new SimpleRedisLock(template, "Seckill");
//            boolean b = simpleRedisLock.tryLock(1200);
//            //判断是否获取刀了
//            if (!b) {
//                return Result.fail("静止重复下单");
//            }
//        try {
//            //获取代理对象
//            ISeckillVoucherService proxy = (ISeckillVoucherService) AopContext.currentProxy();
//            return proxy.SoleSale(voucherId);
//        }finally {
//            simpleRedisLock.unLock();
//        }
////        }
//    }

    @Transactional
    @Override
    public void SoleSale(VoucherOrder voucherOrder){

        //判断用户是否下单了
        Long count = voucherOrderService.query().eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        //如果用户下单了
        System.out.println(count + voucherOrder.toString());
        if(count > 0){
            log.error("请勿重复下单");
            return;
//            return Result.fail("请勿重复下单");
        }
        //使用乐观锁来解决线程安全问题 如果库存大于0 才能实现
        //充足就扣减库存
        boolean flag = voucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)
                .update();

        if (!flag){
//            return Result.fail("库存不足");
            log.error("库存不足");
            return;
        }
        //创建订单
//        voucherOrderService.save(voucherOrder);
//        proxy = (ISeckillVoucherService) AopContext.currentProxy();
//        proxy.SoleSale(voucherOrder);
        save(voucherOrder);
        //返回id
//        return Result.ok(orderId);
    }
}
