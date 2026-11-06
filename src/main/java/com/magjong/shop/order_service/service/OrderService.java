package com.magjong.shop.order_service.service;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.dto.CreateOrderRequest;
import com.magjong.shop.order_service.dto.OrderResponse;
import com.magjong.shop.order_service.messaging.OrderEventPublisher;
import com.magjong.shop.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderEventPublisher orderEventPublisher;
	private final Clock clock;

	public OrderResponse createOrder(CreateOrderRequest request) {
		Order order = Order.createPending(clock.instant());
		request.items().forEach(item -> order.addItem(item.sku(), item.quantity()));
		Order saved = orderRepository.save(order);
		log.info("Created order {} with {} items", saved.getId(), saved.getItems().size());
		orderEventPublisher.publish(saved);
		return OrderResponse.from(saved);
	}
}
