package com.magjong.shop.order_service.service;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.dto.CreateOrderRequest;
import com.magjong.shop.order_service.dto.OrderResponse;
import com.magjong.shop.order_service.messaging.OrderEventPublisher;
import com.magjong.shop.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderEventPublisher orderEventPublisher;
	private final Clock clock;

	public OrderService(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher, Clock clock) {
		this.orderRepository = orderRepository;
		this.orderEventPublisher = orderEventPublisher;
		this.clock = clock;
	}

	public OrderResponse createOrder(CreateOrderRequest request) {
		Order order = Order.createPending(clock.instant());
		request.items().forEach(item -> order.addItem(item.sku(), item.quantity()));
		Order saved = orderRepository.save(order);
		orderEventPublisher.publish(saved);
		return OrderResponse.from(saved);
	}
}
