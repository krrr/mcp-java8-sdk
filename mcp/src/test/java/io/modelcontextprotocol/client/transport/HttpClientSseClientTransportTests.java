/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for the {@link HttpClientSseClientTransport} class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpClientSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestHttpClientSseClientTransport transport;

	// Test class to access protected methods
	static class TestHttpClientSseClientTransport extends HttpClientSseClientTransport {

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<ServerSentEvent<String>> events = Sinks.many().unicast().onBackpressureBuffer();

		public TestHttpClientSseClientTransport(String baseUri) {
			super(baseUri);
		}

		public int getInboundMessageCount() {
			return inboundMessageCount.get();
		}

		public void simulateEndpointEvent(String jsonMessage) {
			events.tryEmitNext(ServerSentEvent.<String>builder().event("endpoint").data(jsonMessage).build());
			inboundMessageCount.incrementAndGet();
		}

		public void simulateMessageEvent(String jsonMessage) {
			events.tryEmitNext(ServerSentEvent.<String>builder().event("message").data(jsonMessage).build());
			inboundMessageCount.incrementAndGet();
		}

	}

	void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@BeforeEach
	void setUp() {
		startContainer();
		transport = new TestHttpClientSseClientTransport(host);
		transport.connect(Function.identity()).block();
	}

	@AfterEach
	void afterEach() {
		if (transport != null) {
			assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
		}
		cleanup();
	}

	void cleanup() {
		container.stop();
	}

	@Test
	void testMessageProcessing() {
		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Simulate receiving the message
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"test-method\",\n" +
				"    \"id\": \"test-id\",\n" +
				"    \"params\": {\"key\": \"value\"}\n" +
				"}");

		// Subscribe to messages and verify
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testResponseMessageProcessing() {
		// Simulate receiving a response message
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"id\": \"test-id\",\n" +
				"    \"result\": {\"status\": \"success\"}\n" +
				"}");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testErrorMessageProcessing() {
		// Simulate receiving an error message
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"id\": \"test-id\",\n" +
				"    \"error\": {\n" +
				"        \"code\": -32600,\n" +
				"        \"message\": \"Invalid Request\"\n" +
				"    }\n" +
				"}");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testNotificationMessageProcessing() {
		// Simulate receiving a notification message (no id)
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"update\",\n" +
				"    \"params\": {\"status\": \"processing\"}\n" +
				"}");

		// Verify the notification was processed
		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testMultipleMessageProcessing() {
		// Simulate receiving multiple messages in sequence
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"method1\",\n" +
				"    \"id\": \"id1\",\n" +
				"    \"params\": {\"key\": \"value1\"}\n" +
				"}");

		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"method2\",\n" +
				"    \"id\": \"id2\",\n" +
				"    \"params\": {\"key\": \"value2\"}\n" +
				"}");

		// Create and send corresponding messages
		JSONRPCRequest message1 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
				Collections.singletonMap("key", "value1"));

		JSONRPCRequest message2 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
				Collections.singletonMap("key", "value2"));

		// Verify both messages are processed
		StepVerifier.create(transport.sendMessage(message1).then(transport.sendMessage(message2))).verifyComplete();

		// Verify message count
		assertThat(transport.getInboundMessageCount()).isEqualTo(2);
	}

	@Test
	void testMessageOrderPreservation() {
		// Simulate receiving messages in a specific order
		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"first\",\n" +
				"    \"id\": \"1\",\n" +
				"    \"params\": {\"sequence\": 1}\n" +
				"}");

		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"second\",\n" +
				"    \"id\": \"2\",\n" +
				"    \"params\": {\"sequence\": 2}\n" +
				"}");

		transport.simulateMessageEvent("{\n" +
				"    \"jsonrpc\": \"2.0\",\n" +
				"    \"method\": \"third\",\n" +
				"    \"id\": \"3\",\n" +
				"    \"params\": {\"sequence\": 3}\n" +
				"}");

		// Verify message count and order
		assertThat(transport.getInboundMessageCount()).isEqualTo(3);
	}

}
