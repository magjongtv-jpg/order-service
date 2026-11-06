package com.magjong.shop.order_service.service;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.domain.OrderStatus;
import com.magjong.shop.order_service.dto.CreateOrderRequest;
import com.magjong.shop.order_service.dto.OrderItemRequest;
import com.magjong.shop.order_service.dto.OrderItemResponse;
import com.magjong.shop.order_service.dto.OrderResponse;
import com.magjong.shop.order_service.messaging.OrderEventPublisher;
import com.magjong.shop.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderEventPublisher orderEventPublisher;

	private OrderService orderService;

	@BeforeEach
	void setUp() {
		orderService = new OrderService(orderRepository, orderEventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
		when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void createOrderSavesPendingOrderWithItemsAndEventId() {
		orderService.createOrder(request());

		ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(saved.capture());
		Order order = saved.getValue();
		assertThat(order.getId()).isNotNull();
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getCreatedAt()).isEqualTo(NOW);
		assertThat(order.getCreatedEventId()).isNotNull();
		assertThat(order.getPublishedAt()).isNull();
		assertThat(order.getItems()).extracting("sku", "quantity")
				.containsExactly(tuple("BOOK-001", 2),
						tuple("PEN-002", 1));
	}

	@Test
	void createOrderPublishesAfterSaving() {
		orderService.createOrder(request());

		var calls = inOrder(orderRepository, orderEventPublisher);
		calls.verify(orderRepository).save(any(Order.class));
		calls.verify(orderEventPublisher).publish(any(Order.class));
	}

	@Test
	void createOrderReturnsPendingResponse() {
		OrderResponse response = orderService.createOrder(request());

		assertThat(response.orderId()).isNotNull();
		assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.createdAt()).isEqualTo(NOW);
		assertThat(response.items()).containsExactly(
				new OrderItemResponse("BOOK-001", 2), new OrderItemResponse("PEN-002", 1));
	}

	private static CreateOrderRequest request() {
		return new CreateOrderRequest(List.of(
				new OrderItemRequest("BOOK-001", 2), new OrderItemRequest("PEN-002", 1)));
	}
}
