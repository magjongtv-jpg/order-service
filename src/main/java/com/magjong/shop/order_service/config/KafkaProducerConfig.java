package com.magjong.shop.order_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfig {

	/**
	 * Replaces Spring Boot's LoggingProducerListener, which logs every failed send at ERROR with the payload.
	 * OrderEventPublisher already logs each failure once, with the order and event ids.
	 */
	@Bean
	ProducerListener<Object, Object> kafkaProducerListener() {
		return new ProducerListener<>() {
		};
	}
}
