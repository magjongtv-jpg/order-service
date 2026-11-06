package com.magjong.shop.order_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(nullable = false)
	private String sku;

	@Column(nullable = false)
	private int quantity;

	protected OrderItem() {
	}

	OrderItem(Order order, String sku, int quantity) {
		this.order = order;
		this.sku = sku;
		this.quantity = quantity;
	}

	public String getSku() {
		return sku;
	}

	public int getQuantity() {
		return quantity;
	}
}
