package com.magjong.shop.order_service.dto;

import com.magjong.shop.order_service.domain.OrderItem;

public record OrderItemResponse(String sku, int quantity) {

	public static OrderItemResponse from(OrderItem item) {
		return new OrderItemResponse(item.getSku(), item.getQuantity());
	}
}
