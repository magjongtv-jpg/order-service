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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderEventRetryJob {

	static final int BATCH_SIZE = 50;
	static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

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
	@SchedulerLock(name = "orderEventRetryJob", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
	public void publishPendingEvents() {
		Instant createdBefore = clock.instant().minus(gracePeriod);
		List<Order> orders = orderRepository.findUnpublished(createdBefore, Limit.of(BATCH_SIZE));
		if (orders.isEmpty()) {
			return;
		}
		log.info("Retrying OrderCreated for {} unpublished orders", orders.size());
		awaitAll(orders.stream().map(orderEventPublisher::publish).toList());
	}

	private void awaitAll(List<CompletableFuture<Void>> sends) {
		try {
			CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
					.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException | TimeoutException e) {
			log.warn("Retry batch did not finish cleanly", e);
		}
	}
}
