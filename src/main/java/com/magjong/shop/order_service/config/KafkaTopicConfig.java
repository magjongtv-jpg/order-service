package com.magjong.shop.order_service.config;

import com.magjong.shop.order_service.messaging.OrderEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

	@Bean
	NewTopic ordersCreatedTopic() {
		return TopicBuilder.name(OrderEventPublisher.ORDERS_CREATED_TOPIC)
				.partitions(3)
				.replicas(1)
				.build();
	}
}
