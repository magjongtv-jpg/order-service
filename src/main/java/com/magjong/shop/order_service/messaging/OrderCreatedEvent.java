package com.magjong.shop.order_service.messaging;

import com.magjong.shop.order_service.domain.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(UUID eventId, String eventType, int eventVersion, Instant occurredAt, Payload payload) {

	public static final String EVENT_TYPE = "OrderCreated";
	public static final int EVENT_VERSION = 1;

	public static OrderCreatedEvent from(Order order) {
		List<Item> items = order.getItems().stream()
				.map(item -> new Item(item.getSku(), item.getQuantity()))
				.toList();
		return new OrderCreatedEvent(order.getCreatedEventId(), EVENT_TYPE, EVENT_VERSION, order.getCreatedAt(),
				new Payload(order.getId(), items));
	}

	public record Payload(UUID orderId, List<Item> items) {
	}

	public record Item(String sku, int quantity) {
	}
}
