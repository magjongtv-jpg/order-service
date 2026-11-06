package com.magjong.shop.order_service.dto;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId, OrderStatus status, List<OrderItemResponse> items, Instant createdAt) {

	public static OrderResponse from(Order order) {
		List<OrderItemResponse> items = order.getItems().stream().map(OrderItemResponse::from).toList();
		return new OrderResponse(order.getId(), order.getStatus(), items, order.getCreatedAt());
	}
}
