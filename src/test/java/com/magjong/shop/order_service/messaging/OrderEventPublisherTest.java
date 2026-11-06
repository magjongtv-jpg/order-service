package com.magjong.shop.order_service.messaging;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.repository.OrderRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

	private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");

	@Mock
	private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

	@Mock
	private OrderRepository orderRepository;

	private OrderEventPublisher publisher;
	private Order order;

	@BeforeEach
	void setUp() {
		publisher = new OrderEventPublisher(kafkaTemplate, orderRepository, Clock.fixed(NOW, ZoneOffset.UTC),
				Runnable::run);
		order = Order.createPending(NOW.minusSeconds(5));
		order.addItem("BOOK-001", 2);
	}

	@Test
	void publishSendsEventKeyedByOrderId() {
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(succeeded());

		publisher.publish(order).join();

		ArgumentCaptor<OrderCreatedEvent> event = ArgumentCaptor.forClass(OrderCreatedEvent.class);
		verify(kafkaTemplate).send(eq("orders.created"), eq(order.getId().toString()), event.capture());
		assertThat(event.getValue().eventId()).isEqualTo(order.getCreatedEventId());
		assertThat(event.getValue().eventType()).isEqualTo("OrderCreated");
		assertThat(event.getValue().eventVersion()).isEqualTo(1);
		assertThat(event.getValue().occurredAt()).isEqualTo(order.getCreatedAt());
		assertThat(event.getValue().payload().orderId()).isEqualTo(order.getId());
		assertThat(event.getValue().payload().items()).containsExactly(new OrderCreatedEvent.Item("BOOK-001", 2));
	}

	@Test
	void publishMarksOrderPublishedOnSuccess() {
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(succeeded());

		publisher.publish(order).join();

		verify(orderRepository).markPublished(order.getId(), NOW);
		verify(orderRepository, never()).markPublishFailed(any(), anyString());
	}

	@Test
	void publishRecordsRootCauseWhenSendFailsAsynchronously() {
		ProducerRecord<String, OrderCreatedEvent> record = new ProducerRecord<>("orders.created", "key", null);
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(
				new KafkaProducerException(record, "Failed to send", new NetworkException("broker down"))));

		publisher.publish(order).join();

		verify(orderRepository).markPublishFailed(order.getId(), "NETWORK_EXCEPTION NetworkException");
		verify(orderRepository, never()).markPublished(any(), any());
	}

	@Test
	void publishRecordsFailureWhenSendThrows() {
		when(kafkaTemplate.send(anyString(), anyString(), any()))
				.thenThrow(new KafkaException("Send failed", new TimeoutException("metadata not available")));

		assertThatCode(() -> publisher.publish(order).join()).doesNotThrowAnyException();

		verify(orderRepository).markPublishFailed(order.getId(), "REQUEST_TIMED_OUT TimeoutException");
	}

	@Test
	void publishDoesNotThrowWhenRecordingOutcomeFails() {
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(succeeded());
		when(orderRepository.markPublished(any(), any())).thenThrow(new IllegalStateException("db down"));

		assertThatCode(() -> publisher.publish(order).join()).doesNotThrowAnyException();
	}

	@Test
	void failureReasonUsesUnknownCodeForNonKafkaErrors() {
		assertThat(OrderEventPublisher.failureReason(new RuntimeException(new IllegalStateException("x"))))
				.isEqualTo("UNKNOWN_SERVER_ERROR IllegalStateException");
	}

	@Test
	void failureReasonIsCutToColumnLength() {
		class AVeryLongExceptionNameThatKeepsGoingAndGoingAndGoingAndGoingAndGoingAndGoingAndGoingException
				extends RuntimeException {
		}

		assertThat(OrderEventPublisher.failureReason(
				new AVeryLongExceptionNameThatKeepsGoingAndGoingAndGoingAndGoingAndGoingAndGoingAndGoingException()))
				.hasSize(OrderEventPublisher.MAX_FAILURE_REASON_LENGTH);
	}

	private static CompletableFuture<SendResult<String, OrderCreatedEvent>> succeeded() {
		return CompletableFuture.completedFuture(null);
	}
}
