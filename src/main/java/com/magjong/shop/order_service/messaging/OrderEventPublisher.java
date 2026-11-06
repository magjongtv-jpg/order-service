package com.magjong.shop.order_service.messaging;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.repository.OrderRepository;
import org.apache.kafka.common.protocol.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
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
	 */
	public CompletableFuture<Void> publish(Order order) {
		UUID orderId = order.getId();
		OrderCreatedEvent event = OrderCreatedEvent.from(order);
		try {
			return kafkaTemplate.send(ORDERS_CREATED_TOPIC, orderId.toString(), event)
					.handleAsync((result, error) -> {
						recordOutcome(orderId, error);
						return null;
					}, callbackExecutor);
		}
		catch (RuntimeException e) {
			recordOutcome(orderId, e);
			return CompletableFuture.completedFuture(null);
		}
	}

	private void recordOutcome(UUID orderId, Throwable error) {
		try {
			if (error == null) {
				orderRepository.markPublished(orderId, clock.instant());
			}
			else {
				String reason = failureReason(error);
				log.warn("Publishing OrderCreated for order {} failed: {}", orderId, reason, error);
				orderRepository.markPublishFailed(orderId, reason);
			}
		}
		catch (RuntimeException e) {
			log.error("Could not record publish outcome for order {}", orderId, e);
		}
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
