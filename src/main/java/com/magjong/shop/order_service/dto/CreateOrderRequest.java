package com.magjong.shop.order_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
		@NotEmpty @Size(max = CreateOrderRequest.MAX_ITEMS) List<@NotNull @Valid OrderItemRequest> items) {

	/** Keeps the OrderCreated event far below Kafka's default 1 MB message size. */
	public static final int MAX_ITEMS = 100;
}
