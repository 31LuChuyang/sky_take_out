package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.xiaoymin.knife4j.core.util.CollectionUtils;
import com.sky.WebSocketServer.WebSocketServer;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.swing.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    WebSocketServer webSocketServer;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //一、处理各种业务异常
        //1、地址簿为空
        AddressBook addressBook;
        // 1. 如果前端传了地址ID → 正常查
        if (ordersSubmitDTO.getAddressBookId() != null) {
            addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        }
        // 2. 如果没传 → 自动用默认地址
        else {
            AddressBook query = new AddressBook();
            query.setUserId(BaseContext.getCurrentId());
            query.setIsDefault(1);

            List<AddressBook> list = addressBookMapper.list(query);

            if (list == null || list.isEmpty()) {
                throw new AddressBookBusinessException("用户没有默认地址");
            }
            addressBook = list.get(0);
        }
        // 最终兜底校验
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //2、购物车为空
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if(shoppingCartList == null || shoppingCartList.size() == 0){
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //二、构造订单数据
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        order.setPhone(addressBook.getPhone());
        order.setConsignee(addressBook.getConsignee());
        order.setAddress(addressBook.getDetail());
        order.setUserId(userId);
        order.setNumber(String.valueOf(System.currentTimeMillis()));//订单号，用时间戳，天然唯一
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());
        //插入1条订单数据
        orderMapper.insert(order);

        //订单明细数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();//订单明细
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(order.getId());//设置当前订单明细关联的订单
            orderDetailList.add(orderDetail);
        }
        //向明细表中插入n条数据
        orderDetailMapper.insertBatch(orderDetailList);

        //清空当前用户的购物车数据
        shoppingCartMapper.deleteByUserId(userId);
        //封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO
                .builder()
                .id(order.getId())
                .orderTime(order.getOrderTime())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .build();

        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
/*
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
*/

        log.info("跳过微信支付，支付成功！");
        paySuccess(ordersPaymentDTO.getOrderNumber());
        return new OrderPaymentVO();

    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单号查询当前用户的订单
        Orders ordersDB = orderMapper.getByNumberAndUserId(outTradeNo, userId);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
        //通过Websocket推送消息 type orderId content
        Map map=new HashMap();
        map.put("type",1);
        map.put("orderId",ordersDB.getId());
        map.put("content","订单号"+outTradeNo);

        String json = JSONObject.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 历史订单查询
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        //开始分页
        PageHelper.startPage(pageNum,pageSize);

        //构造查询条件
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        //分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> list = new ArrayList();

        //查询订单明细，并封装入OrderVO进行响应
        if(page != null && page.getTotal() > 0){//数据库总记录数>0
            for (Orders orders : page) {
                Long orderId = orders.getId(); //订单id
                //查询订单明细
                List<OrderDetail> orderDetailLists = orderDetailMapper.getByOrderId(orderId);
                //封装OrderVO
                OrderVO orderVO = new OrderVO();
                //复制订单主信息
                //把orders里的字段：id,number,amount,status 复制到：OrderVO里。
                BeanUtils.copyProperties(orders, orderVO);
                //追加订单明细
                orderVO.setOrderDetailList(orderDetailLists);
                //加入结果集合
                list.add(orderVO);
            }
        }
        //page.getTotal是总记录数，list是当前页的数据
        return new PageResult(page.getTotal(),list);
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    public OrderVO detail(Long id) {
        //根据id查询订单
         Orders orders = orderMapper.getById(id);
         //查询该订单对应的菜品//套餐明细
         List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        //订单详情封装给orderVO
         OrderVO orderVO = new OrderVO();
         BeanUtils.copyProperties(orders,orderVO);
         orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 取消订单
     * @param id
     */
    public void userCancelById(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);
        //判断订单是否存在
        if(ordersDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //判断订单状态是否允许取消
        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        //订单状态大于2，不可以取消订单
        if(ordersDB.getStatus() > 2){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        //订单处于待接单状态下取消，需要进行退款
        if(ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            //调用微信支付退款接口
            //  weChatPayUtil.refund(
            //          ordersDB.getNumber(), //商户订单号
            //          ordersDB.getNumber(), //商户退款单号
            //          new BigDecimal(0.01),//退款金额，单位 元
            //          new BigDecimal(0.01));//原订单金额

            //支付状态修改为 退款
            ordersDB.setPayStatus(Orders.REFUND);
        }
        //更新订单状态，取消原因，取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 再来一单
     * @param id
     */
    public void repetition(Long id) {
        //查询当前用户id
        Long userId = BaseContext.getCurrentId();
        //根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        if(orderDetailList == null || orderDetailList.isEmpty()){
            throw new OrderBusinessException("订单详情为空");
        }
        //订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            //将原订单详情里面的菜品信息重新复制到购物车对象中
            //x是shoppingCartList,Java8省略了
            BeanUtils.copyProperties(x,shoppingCart,"id"); //排除id
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());
        //将购物车对象批量添加到数据库中
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 客户催单
     * @param id
     */
    public void reminder(Long id) {
        //查询订单是否存在
        Orders orders = orderMapper.getById(id);
        if(orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //基于Websocket实现催单
        Map map = new HashMap();
        map.put("type", 2);//2代表用户催单
        map.put("orderId", id);
        map.put("content", "订单号：" + orders.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        //部分订单要额外返回菜品信息，故需将page数据转成vo
        List<OrderVO> orderVOList = getOrderVOList(page);
        //返回订单总记录数和订单下的菜品信息
        return new PageResult(page.getTotal(),orderVOList);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page){
        List<OrderVO> orderVOList = new ArrayList<>();
        //拿到当前页所有订单
        List<Orders> ordersList = page.getResult();

        if (CollectionUtils.isEmpty(ordersList)) {
            for(Orders orders : ordersList){
                OrderVO orderVO = new OrderVO();
                //一个对象的数据来自多张表，仅靠copyProperties远远不够，需要自己组装

                //把orders的属性拷贝给orderVO
                BeanUtils.copyProperties(orders,orderVO);
                String orderDishes = getOrderDishStr(orders);
                //将订单菜品的信息封装到orderVO，再添加到集合
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }
    /**
     * 根据订单id获取菜品信息里的字符串
     * @param orders
     * @return
     */
    private String getOrderDishStr(Orders orders){
        //根据订单id查询菜品详情信息
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        //将每条订单菜品信息拼接成字符串，格式为：宫保鸡丁*3
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        return String.join("",orderDishList);
    }


    /**
     * 各个状态的订单数量统计
     * @return
     */
    public OrderStatisticsVO statistics() {
        Integer toBeConfirmed = orderMapper.getcountStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.getcountStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.getcountStatus(Orders.DELIVERY_IN_PROGRESS);

        //将查询到的数据封装给vo
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = Orders
                .builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.update(orders);
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());
        //判断状态，订单状态为 2-待接单 才可以拒单
        if(ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
         /*
        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            //用户已支付，需要退款
            String refund = weChatPayUtil.refund(
                    ordersDB.getNumber(),
                    ordersDB.getNumber(),
                    new BigDecimal(0.01),
                    new BigDecimal(0.01));
            log.info("申请退款：{}", refund);
        }
        */

        //拒单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 取消订单
     * @param ordersCancelDTO
     */
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception{
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());
        //管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 派送订单
     * @param id
     */
    public void delivery(Long id) {
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在，且状态为3
        if(ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
    }

    /**
     * 完成订单
     * @param id
     */
    public void complete(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);
        //校验订单是否存在，并且状态为4
        if(ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //更新订单状态,状态转为完成
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        //更新数据库
        orderMapper.update(orders);
    }
}
