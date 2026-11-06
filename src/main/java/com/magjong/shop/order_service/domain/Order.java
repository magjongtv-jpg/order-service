package com.magjong.shop.order_service.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order implements Persistable<UUID> {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "created_event_id", nullable = false)
	private UUID createdEventId;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "publish_failure_reason")
	private String publishFailureReason;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItem> items = new ArrayList<>();

	@Transient
	@Getter(AccessLevel.NONE)
	private boolean isNew = true;

	public static Order createPending(Instant now) {
		Order order = new Order();
		Instant createdAt = now.truncatedTo(ChronoUnit.MICROS);
		order.id = UUID.randomUUID();
		order.status = OrderStatus.PENDING;
		order.createdAt = createdAt;
		order.updatedAt = createdAt;
		order.createdEventId = UUID.randomUUID();
		return order;
	}

	public void addItem(String sku, int quantity) {
		items.add(new OrderItem(this, sku, quantity));
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}

	public List<OrderItem> getItems() {
		return Collections.unmodifiableList(items);
	}
}
