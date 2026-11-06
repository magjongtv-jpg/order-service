package com.magjong.shop.order_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OrderItemRequest(
		@NotBlank @Size(max = 64) String sku,
		@NotNull @Min(1) Integer quantity) {
}
