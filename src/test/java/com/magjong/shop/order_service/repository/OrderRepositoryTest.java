package com.magjong.shop.order_service.repository;

import com.magjong.shop.order_service.domain.Order;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");
	private static final Instant CUTOFF = NOW.minus(Duration.ofMinutes(1));

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void findUnpublishedReturnsOnlyUnpublishedOrdersOlderThanCutoffOldestFirst() {
		Order older = save(CUTOFF.minusSeconds(60));
		Order old = save(CUTOFF.minusSeconds(30));
		Order recent = save(CUTOFF.plusSeconds(10));
		Order published = save(CUTOFF.minusSeconds(90));
		orderRepository.markPublished(published.getId(), NOW);
		entityManager.clear();

		List<Order> unpublished = orderRepository.findUnpublished(CUTOFF, Limit.of(10));

		assertThat(unpublished).extracting(Order::getId).containsExactly(older.getId(), old.getId());
		assertThat(unpublished).extracting(Order::getId).doesNotContain(recent.getId(), published.getId());
	}

	@Test
	void findUnpublishedRespectsLimit() {
		Order oldest = save(CUTOFF.minusSeconds(60));
		save(CUTOFF.minusSeconds(30));
		entityManager.clear();

		assertThat(orderRepository.findUnpublished(CUTOFF, Limit.of(1)))
				.extracting(Order::getId)
				.containsExactly(oldest.getId());
	}

	@Test
	void findUnpublishedLoadsItemsWithTheOrder() {
		save(CUTOFF.minusSeconds(60));
		entityManager.clear();

		Order order = orderRepository.findUnpublished(CUTOFF, Limit.of(10)).getFirst();

		assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(order, "items"))
				.isTrue();
		assertThat(order.getItems()).extracting("sku").containsExactly("BOOK-001");
	}

	@Test
	void markPublishedKeepsTheFirstPublishTime() {
		Order order = save(CUTOFF.minusSeconds(60));

		assertThat(orderRepository.markPublished(order.getId(), NOW)).isEqualTo(1);
		assertThat(orderRepository.markPublished(order.getId(), NOW.plusSeconds(30))).isZero();

		entityManager.clear();
		assertThat(orderRepository.findById(order.getId()).orElseThrow().getPublishedAt()).isEqualTo(NOW);
	}

	@Test
	void markPublishFailedDoesNotTouchPublishedOrders() {
		Order order = save(CUTOFF.minusSeconds(60));
		orderRepository.markPublished(order.getId(), NOW);

		assertThat(orderRepository.markPublishFailed(order.getId(), "NETWORK_EXCEPTION NetworkException")).isZero();

		entityManager.clear();
		assertThat(orderRepository.findById(order.getId()).orElseThrow().getPublishFailureReason()).isNull();
	}

	@Test
	void markPublishFailedRecordsReasonOnUnpublishedOrders() {
		Order order = save(CUTOFF.minusSeconds(60));

		assertThat(orderRepository.markPublishFailed(order.getId(), "NETWORK_EXCEPTION NetworkException"))
				.isEqualTo(1);

		entityManager.clear();
		assertThat(orderRepository.findById(order.getId()).orElseThrow().getPublishFailureReason())
				.isEqualTo("NETWORK_EXCEPTION NetworkException");
	}

	private Order save(Instant createdAt) {
		Order order = Order.createPending(createdAt);
		order.addItem("BOOK-001", 1);
		return orderRepository.saveAndFlush(order);
	}
}
