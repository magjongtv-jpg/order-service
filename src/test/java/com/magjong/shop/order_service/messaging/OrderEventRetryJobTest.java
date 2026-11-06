package com.magjong.shop.order_service.messaging;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrderEventRetryJobTest {

	private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");
	private static final Duration GRACE_PERIOD = Duration.ofMinutes(1);

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderEventPublisher orderEventPublisher;

	private OrderEventRetryJob job;

	@BeforeEach
	void setUp() {
		job = new OrderEventRetryJob(orderRepository, orderEventPublisher, Clock.fixed(NOW, ZoneOffset.UTC),
				GRACE_PERIOD);
	}

	@Test
	void publishPendingEventsLooksForOrdersOlderThanGracePeriod() {
		when(orderRepository.findUnpublished(any(), any())).thenReturn(List.of());

		job.publishPendingEvents();

		verify(orderRepository).findUnpublished(NOW.minus(GRACE_PERIOD), Limit.of(OrderEventRetryJob.BATCH_SIZE));
	}

	@Test
	void publishPendingEventsPublishesEachUnpublishedOrder() {
		Order first = Order.createPending(NOW.minusSeconds(120));
		Order second = Order.createPending(NOW.minusSeconds(90));
		when(orderRepository.findUnpublished(any(), any())).thenReturn(List.of(first, second));
		when(orderEventPublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		job.publishPendingEvents();

		verify(orderEventPublisher).publish(first);
		verify(orderEventPublisher).publish(second);
	}

	@Test
	void publishPendingEventsWaitsForSendsToFinish() {
		Order order = Order.createPending(NOW.minusSeconds(120));
		CompletableFuture<Void> send = new CompletableFuture<>();
		when(orderRepository.findUnpublished(any(), any())).thenReturn(List.of(order));
		when(orderEventPublisher.publish(order)).thenReturn(send);
		Thread.ofVirtual().start(() -> {
			sleep(Duration.ofMillis(200));
			send.complete(null);
		});

		job.publishPendingEvents();

		assertThat(send).isDone();
	}

	@Test
	void publishPendingEventsLogsUnfinishedOrdersWhenInterrupted(CapturedOutput output) {
		Order order = Order.createPending(NOW.minusSeconds(120));
		when(orderRepository.findUnpublished(any(), any())).thenReturn(List.of(order));
		when(orderEventPublisher.publish(order)).thenReturn(new CompletableFuture<>());

		Thread.currentThread().interrupt();
		job.publishPendingEvents();

		assertThat(Thread.interrupted()).isTrue();
		assertThat(output).contains("Retry batch was interrupted; unfinished orders: [" + order.getId() + "]");
	}

	@Test
	void publishPendingEventsDoesNothingWhenNothingIsPending() {
		when(orderRepository.findUnpublished(any(), any())).thenReturn(List.of());

		job.publishPendingEvents();

		verifyNoInteractions(orderEventPublisher);
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
