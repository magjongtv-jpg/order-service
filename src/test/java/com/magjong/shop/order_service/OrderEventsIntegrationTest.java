package com.magjong.shop.order_service;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.messaging.OrderEventRetryJob;
import com.magjong.shop.order_service.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "orders.events.retry-interval=PT1H")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OrderEventsIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderEventRetryJob retryJob;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private KafkaContainer kafka;

	@BeforeEach
	void releaseJobLock() {
		setJobLockUntil("timezone('utc', CURRENT_TIMESTAMP) - INTERVAL '1 second'");
	}

	@Test
	void postOrderSavesPendingOrderAndPublishesOrderCreated() throws Exception {
		String body = mockMvc.perform(post("/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"items":[{"sku":"BOOK-001","quantity":2}]}"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID orderId = UUID.fromString(JSON.readTree(body).get("orderId").asString());

		Order published = awaitPublished(orderId);
		assertThat(published.getStatus().name()).isEqualTo("PENDING");
		assertThat(published.getPublishFailureReason()).isNull();

		JsonNode event = JSON.readTree(consume(orderId).value());
		assertThat(fieldNames(event)).containsExactlyInAnyOrder(
				"eventId", "eventType", "eventVersion", "occurredAt", "payload");
		assertThat(event.get("eventId").asString()).isEqualTo(published.getCreatedEventId().toString());
		assertThat(event.get("eventType").asString()).isEqualTo("OrderCreated");
		assertThat(event.get("eventVersion").asInt()).isEqualTo(1);
		assertThat(Instant.parse(event.get("occurredAt").asString())).isEqualTo(published.getCreatedAt());
		JsonNode payload = event.get("payload");
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder("orderId", "items");
		assertThat(payload.get("orderId").asString()).isEqualTo(orderId.toString());
		assertThat(payload.get("items").get(0).get("sku").asString()).isEqualTo("BOOK-001");
		assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
	}

	@Test
	void postOrderCarriesOneTraceFromRequestToKafka(CapturedOutput output) throws Exception {
		String body = mockMvc.perform(post("/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"items":[{"sku":"BOOK-001","quantity":2}]}"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID orderId = UUID.fromString(JSON.readTree(body).get("orderId").asString());
		awaitPublished(orderId);

		ConsumerRecord<String, String> record = consume(orderId);
		assertThat(record.headers().lastHeader("traceparent")).isNotNull();
		String traceId = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8)
				.split("-")[1];
		assertThat(logLine(output, "Created order " + orderId)).contains(traceId);
		assertThat(logLine(output, "for order " + orderId + " to partition")).contains(traceId);
	}

	@Test
	void retryJobPublishesOrdersThatWereNotPublished() {
		Order order = saveUnpublishedOrder();

		retryJob.publishPendingEvents();

		Order published = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(published.getPublishedAt()).isNotNull();
		JsonNode event = JSON.readTree(consume(order.getId()).value());
		assertThat(event.get("eventId").asString()).isEqualTo(order.getCreatedEventId().toString());
	}

	@Test
	void retryJobSkipsRunWhileAnotherInstanceHoldsTheLock() {
		Order order = saveUnpublishedOrder();
		setJobLockUntil("timezone('utc', CURRENT_TIMESTAMP) + INTERVAL '1 hour'");

		retryJob.publishPendingEvents();

		assertThat(orderRepository.findById(order.getId()).orElseThrow().getPublishedAt()).isNull();

		releaseJobLock();
		retryJob.publishPendingEvents();

		assertThat(orderRepository.findById(order.getId()).orElseThrow().getPublishedAt()).isNotNull();
	}

	/**
	 * ShedLock only inserts its lock row once per job and afterwards only updates it,
	 * so tests change lock_until instead of deleting the row.
	 */
	private void setJobLockUntil(String lockUntilSql) {
		jdbcTemplate.update("""
				INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
				VALUES ('orderEventRetryJob', %s, timezone('utc', CURRENT_TIMESTAMP), 'other-instance')
				ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by"""
				.formatted(lockUntilSql));
	}

	private Order saveUnpublishedOrder() {
		Order order = Order.createPending(Instant.now().minus(Duration.ofMinutes(5)));
		order.addItem("PEN-002", 1);
		return orderRepository.save(order);
	}

	private Order awaitPublished(UUID orderId) {
		return await(() -> orderRepository.findById(orderId).filter(order -> order.getPublishedAt() != null));
	}

	private static String logLine(CapturedOutput output, String text) {
		return output.getAll().lines()
				.filter(line -> line.contains(text))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No log line contains: " + text));
	}

	private ConsumerRecord<String, String> consume(UUID orderId) {
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		try (KafkaConsumer<String, String> consumer =
				new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
			consumer.subscribe(List.of("orders.created"));
			return await(() -> {
				for (ConsumerRecord<String, String> candidate : consumer.poll(Duration.ofMillis(500))) {
					if (orderId.toString().equals(candidate.key())) {
						return Optional.of(candidate);
					}
				}
				return Optional.empty();
			});
		}
	}

	private static <T> T await(Supplier<Optional<T>> condition) {
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			Optional<T> result = condition.get();
			if (result.isPresent()) {
				return result.get();
			}
			sleep();
		}
		throw new AssertionError("Condition not met within " + TIMEOUT);
	}

	private static void sleep() {
		try {
			Thread.sleep(200);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting", e);
		}
	}

	private static List<String> fieldNames(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).toList();
	}
}
