package com.magjong.shop.order_service.repository;

import com.magjong.shop.order_service.domain.Order;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

	default List<Order> findUnpublished(Instant createdBefore, Limit limit) {
		List<UUID> ids = findUnpublishedIds(createdBefore, limit);
		return ids.isEmpty() ? List.of() : findAllWithItemsByIdIn(ids);
	}

	@Query("""
			select o.id from Order o
			where o.publishedAt is null and o.createdAt < :createdBefore
			order by o.createdAt""")
	List<UUID> findUnpublishedIds(Instant createdBefore, Limit limit);

	@EntityGraph(attributePaths = "items")
	@Query("select o from Order o where o.id in :ids order by o.createdAt")
	List<Order> findAllWithItemsByIdIn(Collection<UUID> ids);

	@Transactional
	@Modifying
	@Query("update Order o set o.publishedAt = :publishedAt where o.id = :id and o.publishedAt is null")
	int markPublished(UUID id, Instant publishedAt);

	@Transactional
	@Modifying
	@Query("update Order o set o.publishFailureReason = :reason where o.id = :id and o.publishedAt is null")
	int markPublishFailed(UUID id, String reason);
}
