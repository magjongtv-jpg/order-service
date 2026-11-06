package com.magjong.shop.order_service.controller;

import com.magjong.shop.order_service.domain.OrderStatus;
import com.magjong.shop.order_service.dto.CreateOrderRequest;
import com.magjong.shop.order_service.dto.OrderItemRequest;
import com.magjong.shop.order_service.dto.OrderItemResponse;
import com.magjong.shop.order_service.dto.OrderResponse;
import com.magjong.shop.order_service.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@ExtendWith(OutputCaptureExtension.class)
class OrderControllerTest {

	private static final UUID ORDER_ID = UUID.fromString("b3d9c1e0-5a1f-4e47-8a5b-2c7d8e9f0a12");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void createOrderReturnsCreatedOrder() throws Exception {
		when(orderService.createOrder(any())).thenReturn(new OrderResponse(ORDER_ID, OrderStatus.PENDING,
				List.of(new OrderItemResponse("BOOK-001", 2)), Instant.parse("2026-11-05T10:15:30Z")));

		post("""
				{"items":[{"sku":"BOOK-001","quantity":2}]}""")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "http://localhost/orders/" + ORDER_ID))
				.andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.items[0].sku").value("BOOK-001"))
				.andExpect(jsonPath("$.items[0].quantity").value(2))
				.andExpect(jsonPath("$.createdAt").value("2026-11-05T10:15:30Z"));

		verify(orderService).createOrder(new CreateOrderRequest(List.of(new OrderItemRequest("BOOK-001", 2))));
	}

	@Test
	void createOrderRejectsMissingItems() throws Exception {
		expectValidationError("{}", "items");
	}

	@Test
	void createOrderRejectsEmptyItems() throws Exception {
		expectValidationError("""
				{"items":[]}""", "items");
	}

	@Test
	void createOrderRejectsMoreThanMaxItems() throws Exception {
		String items = String.join(",", Collections.nCopies(CreateOrderRequest.MAX_ITEMS + 1,
				"""
				{"sku":"BOOK-001","quantity":1}"""));
		expectValidationError("{\"items\":[" + items + "]}", "items");
	}

	@Test
	void createOrderAcceptsMaxItems() throws Exception {
		when(orderService.createOrder(any())).thenReturn(new OrderResponse(ORDER_ID, OrderStatus.PENDING,
				List.of(), Instant.parse("2026-11-05T10:15:30Z")));
		String items = String.join(",", Collections.nCopies(CreateOrderRequest.MAX_ITEMS,
				"""
				{"sku":"BOOK-001","quantity":1}"""));

		post("{\"items\":[" + items + "]}").andExpect(status().isCreated());
	}

	@Test
	void createOrderRejectsBlankSku() throws Exception {
		expectValidationError("""
				{"items":[{"sku":" ","quantity":1}]}""", "items[0].sku");
	}

	@Test
	void createOrderRejectsSkuLongerThan64Characters() throws Exception {
		expectValidationError("""
				{"items":[{"sku":"%s","quantity":1}]}""".formatted("A".repeat(65)), "items[0].sku");
	}

	@Test
	void createOrderRejectsQuantityBelowOne() throws Exception {
		expectValidationError("""
				{"items":[{"sku":"BOOK-001","quantity":0}]}""", "items[0].quantity");
	}

	@Test
	void createOrderRejectsMissingQuantity() throws Exception {
		expectValidationError("""
				{"items":[{"sku":"BOOK-001"}]}""", "items[0].quantity");
	}

	@Test
	void createOrderRejectsMalformedJson() throws Exception {
		post("{\"items\": [")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderReturnsProblemDetailForUnexpectedErrors() throws Exception {
		when(orderService.createOrder(any())).thenThrow(new IllegalStateException("boom"));

		post("""
				{"items":[{"sku":"BOOK-001","quantity":2}]}""")
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
	}

	@Test
	void unexpectedErrorIsLoggedWithMethodAndPath(CapturedOutput output) throws Exception {
		when(orderService.createOrder(any())).thenThrow(new IllegalStateException("boom"));

		post("""
				{"items":[{"sku":"BOOK-001","quantity":2}]}""");

		assertThat(output).contains("Unexpected error handling POST /orders").contains("IllegalStateException: boom");
	}

	@Test
	void validationErrorIsLoggedWithFieldNamesOnly(CapturedOutput output) throws Exception {
		post("""
				{"items":[{"sku":"SECRET-SKU-VALUE","quantity":0}]}""");

		assertThat(output).contains("Rejected POST /orders with 400: invalid fields [items[0].quantity]")
				.doesNotContain("SECRET-SKU-VALUE");
	}

	@Test
	void problemDetailCarriesTraceIdWhenTracing() throws Exception {
		MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
		when(orderService.createOrder(any())).thenThrow(new IllegalStateException("boom"));

		post("""
				{"items":[{"sku":"BOOK-001","quantity":2}]}""")
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"));
	}

	@Test
	void validationProblemDetailCarriesTraceIdWhenTracing() throws Exception {
		MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

		post("{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"));
	}

	private void expectValidationError(String body, String field) throws Exception {
		post(body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.errors[*].field", hasItem(field)));

		verifyNoInteractions(orderService);
	}

	private ResultActions post(String body) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.post("/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
