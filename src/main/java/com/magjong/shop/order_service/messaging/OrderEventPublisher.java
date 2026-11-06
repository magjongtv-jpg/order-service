package com.magjong.shop.order_service.messaging;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.repository.OrderRepository;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.protocol.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class OrderEventPublisher {

	public static final String ORDERS_CREATED_TOPIC = "orders.created";

	static final int MAX_FAILURE_REASON_LENGTH = 100;

	private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
	private static final ContextSnapshotFactory CONTEXT = ContextSnapshotFactory.builder().build();

	private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
	private final OrderRepository orderRepository;
	private final Clock clock;
	private final Executor callbackExecutor;

	public OrderEventPublisher(KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate, OrderRepository orderRepository,
			Clock clock, @Qualifier("applicationTaskExecutor") Executor callbackExecutor) {
		this.kafkaTemplate = kafkaTemplate;
		this.orderRepository = orderRepository;
		this.clock = clock;
		this.callbackExecutor = callbackExecutor;
	}

	/**
	 * Sends the order's OrderCreated event and records the outcome on the order.
	 * Never throws; the returned future completes once the outcome is recorded.
	 * The caller's trace context is restored on the callback thread so its logs share the trace id.
	 */
	public CompletableFuture<Void> publish(Order order) {
		OrderCreatedEvent event = OrderCreatedEvent.from(order);
		ContextSnapshot context = CONTEXT.captureAll();
		try {
			return kafkaTemplate.send(ORDERS_CREATED_TOPIC, order.getId().toString(), event)
					.handleAsync((result, error) -> {
						try (ContextSnapshot.Scope scope = context.setThreadLocals()) {
							recordOutcome(event, result, error);
						}
						return null;
					}, callbackExecutor);
		}
		catch (RuntimeException e) {
			recordOutcome(event, null, e);
			return CompletableFuture.completedFuture(null);
		}
	}

	private void recordOutcome(OrderCreatedEvent event, SendResult<String, OrderCreatedEvent> result,
			Throwable error) {
		UUID orderId = event.payload().orderId();
		try {
			if (error == null) {
				recordPublished(event, result.getRecordMetadata());
			}
			else {
				recordFailure(event, error);
			}
		}
		catch (RuntimeException e) {
			log.error("Could not record publish outcome of event {} for order {}", event.eventId(), orderId, e);
		}
	}

	private void recordPublished(OrderCreatedEvent event, RecordMetadata metadata) {
		UUID orderId = event.payload().orderId();
		log.info("Published OrderCreated event {} for order {} to partition {} at offset {}",
				event.eventId(), orderId, metadata.partition(), metadata.offset());
		if (orderRepository.markPublished(orderId, clock.instant()) == 0) {
			log.debug("Order {} was already marked as published", orderId);
		}
	}

	private void recordFailure(OrderCreatedEvent event, Throwable error) {
		UUID orderId = event.payload().orderId();
		String reason = failureReason(error);
		log.warn("Publishing OrderCreated event {} for order {} failed: {}", event.eventId(), orderId, reason, error);
		orderRepository.markPublishFailed(orderId, reason);
	}

	static String failureReason(Throwable error) {
		Throwable root = rootCause(error);
		String reason = Errors.forException(root).name() + " " + root.getClass().getSimpleName();
		return reason.length() <= MAX_FAILURE_REASON_LENGTH ? reason : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
	}

	private static Throwable rootCause(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}
}
