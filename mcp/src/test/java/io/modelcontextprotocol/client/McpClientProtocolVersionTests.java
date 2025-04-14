/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import io.modelcontextprotocol.MockMcpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP protocol version negotiation and compatibility.
 */
class McpClientProtocolVersionTests {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("test-client", "1.0.0");

	@Test
	void shouldUseLatestVersionByDefault() {
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.getParams()).isInstanceOf(McpSchema.InitializeRequest.class);
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.getParams();
				assertThat(initRequest.getProtocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.getId(),
						new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).assertNext(result -> {
				assertThat(result.getProtocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);
			}).verifyComplete();

		}
		finally {
			// Ensure cleanup happens even if test fails
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldNegotiateSpecificVersion() {
		String oldVersion = "0.1.0";
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		client.setProtocolVersions(Arrays.asList(oldVersion, McpSchema.LATEST_PROTOCOL_VERSION));

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.getParams()).isInstanceOf(McpSchema.InitializeRequest.class);
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.getParams();
				assertThat(initRequest.getProtocolVersion()).isIn(Arrays.asList(oldVersion, McpSchema.LATEST_PROTOCOL_VERSION));

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.getId(),
						new McpSchema.InitializeResult(oldVersion, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).assertNext(result -> {
				assertThat(result.getProtocolVersion()).isEqualTo(oldVersion);
			}).verifyComplete();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldFailForUnsupportedVersion() {
		String unsupportedVersion = "999.999.999";
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.getParams()).isInstanceOf(McpSchema.InitializeRequest.class);

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.getId(),
						new McpSchema.InitializeResult(unsupportedVersion, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).expectError(McpError.class).verify();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldUseHighestVersionWhenMultipleSupported() {
		String oldVersion = "0.1.0";
		String middleVersion = "0.2.0";
		String latestVersion = McpSchema.LATEST_PROTOCOL_VERSION;

		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		client.setProtocolVersions(Arrays.asList(oldVersion, middleVersion, latestVersion));

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.getParams();
				assertThat(initRequest.getProtocolVersion()).isEqualTo(latestVersion);

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.getId(),
						new McpSchema.InitializeResult(latestVersion, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).assertNext(result -> {
				assertThat(result.getProtocolVersion()).isEqualTo(latestVersion);
			}).verifyComplete();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}

	}

}
