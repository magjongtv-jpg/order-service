package com.magjong.shop.order_service.messaging;

import com.magjong.shop.order_service.domain.Order;
import com.magjong.shop.order_service.repository.OrderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderEventRetryJob {

	static final int BATCH_SIZE = 50;
	static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
	/**
	 * Limits how long one run keeps starting sends. A send can block for up to max.block.ms when the broker
	 * is unreachable, so without a limit a batch could outlast lockAtMostFor and overlap with another
	 * instance. Worst case per run: DISPATCH_BUDGET + max.block.ms + SEND_TIMEOUT, below lockAtMostFor.
	 */
	static final Duration DISPATCH_BUDGET = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(OrderEventRetryJob.class);

	private final OrderRepository orderRepository;
	private final OrderEventPublisher orderEventPublisher;
	private final Clock clock;
	private final Duration gracePeriod;

	public OrderEventRetryJob(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher, Clock clock,
			@Value("${orders.events.grace-period}") Duration gracePeriod) {
		this.orderRepository = orderRepository;
		this.orderEventPublisher = orderEventPublisher;
		this.clock = clock;
		this.gracePeriod = gracePeriod;
	}

	@Scheduled(fixedDelayString = "${orders.events.retry-interval}")
	@SchedulerLock(name = "orderEventRetryJob", lockAtMostFor = "PT3M", lockAtLeastFor = "PT10S")
	public void publishPendingEvents() {
		Instant createdBefore = clock.instant().minus(gracePeriod);
		List<Order> orders = orderRepository.findUnpublished(createdBefore, Limit.of(BATCH_SIZE));
		if (orders.isEmpty()) {
			return;
		}
		log.info("Retrying OrderCreated for {} unpublished orders, oldest created at {}",
				orders.size(), orders.getFirst().getCreatedAt());
		awaitAll(dispatch(orders));
	}

	private Map<UUID, CompletableFuture<Void>> dispatch(List<Order> orders) {
		Instant deadline = clock.instant().plus(DISPATCH_BUDGET);
		Map<UUID, CompletableFuture<Void>> sends = new LinkedHashMap<>();
		for (Order order : orders) {
			if (clock.instant().isAfter(deadline)) {
				log.warn("Stopped retry batch after {} of {} orders; dispatch budget of {} used up",
						sends.size(), orders.size(), DISPATCH_BUDGET);
				break;
			}
			sends.put(order.getId(), orderEventPublisher.publish(order));
		}
		return sends;
	}

	private void awaitAll(Map<UUID, CompletableFuture<Void>> sends) {
		try {
			CompletableFuture.allOf(sends.values().toArray(CompletableFuture[]::new))
					.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Retry batch was interrupted; unfinished orders: {}", unfinished(sends));
		}
		catch (ExecutionException | TimeoutException e) {
			log.warn("Retry batch did not finish within {}; unfinished orders: {}", SEND_TIMEOUT, unfinished(sends), e);
		}
	}

	private static List<UUID> unfinished(Map<UUID, CompletableFuture<Void>> sends) {
		return sends.entrySet().stream()
				.filter(send -> !send.getValue().isDone())
				.map(Map.Entry::getKey)
				.toList();
	}
}
