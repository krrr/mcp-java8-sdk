/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.var;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import reactor.test.StepVerifier;


import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class HttpServletSseServerTransportIntegrationTests {

	private static final int PORT = 8184;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private HttpServletSseServerTransport mcpServerTransport;

	McpClient.SyncSpec clientBuilder;

	private Tomcat tomcat;

	@BeforeEach
	public void before() {
		tomcat = new Tomcat();
		tomcat.setPort(PORT);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);

		// Create and configure the transport
		mcpServerTransport = new HttpServletSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(mcpServerTransport);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		try {
			var connector = tomcat.getConnector();
			connector.setAsyncTimeout(3000);
			tomcat.start();
			assertThat(tomcat.getServer().getState() == LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(new HttpClientSseClientTransport("http://localhost:" + PORT));
	}

	@AfterEach
	public void after() {
		if (mcpServerTransport != null) {
			mcpServerTransport.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@Test
	void testCreateMessageWithoutInitialization() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		var messages = Collections
			.singletonList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(Collections.emptyList(), 1.0, 1.0, 1.0);

		var request = new McpSchema.CreateMessageRequest(messages, modelPrefs, null,
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, Collections.emptyList(),Collections.emptyMap());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be initialized. Call the initialize method first!");
		});
	}

	@Test
	void testCreateMessageWithoutSamplingCapabilities() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0")).build();

		InitializeResult initResult = client.initialize();
		assertThat(initResult).isNotNull();

		var messages = Collections
			.singletonList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(Collections.emptyList(), 1.0, 1.0, 1.0);

		var request = new McpSchema.CreateMessageRequest(messages, modelPrefs, null,
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, Collections.emptyList(),Collections.emptyMap());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with sampling capabilities");
		});
	}

	@Test
	void testCreateMessageSuccess() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		InitializeResult initResult = client.initialize();
		assertThat(initResult).isNotNull();

		var messages = Collections
			.singletonList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(Collections.emptyList(), 1.0, 1.0, 1.0);

		var request = new McpSchema.CreateMessageRequest(messages, modelPrefs, null,
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, Collections.emptyList(),Collections.emptyMap());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).consumeNextWith(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getRole()).isEqualTo(Role.USER);
			assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
			assertThat(result.getModel()).isEqualTo("MockModelName");
			assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
		}).verifyComplete();
	}

	@Test
	void testRootsSuccess() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		assertThat(mcpServer.listRoots().getRoots()).containsAll(roots);

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		mcpClient.close();
		mcpServer.close();
	}

	String emptyJsonSchema = "{\n" +
			"    \"$schema\": \"http://json-schema.org/draft-07/schema#\",\n" +
			"    \"type\": \"object\",\n" +
			"    \"properties\": {}\n" +
			"}";

	@Test
	void testToolCallSuccess() {
		var callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolRegistration tool1 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), request -> {
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1",Collections.emptyMap()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testToolListChangeHandlingSuccess() {
		var callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolRegistration tool1 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), request -> {
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		AtomicReference<List<Tool>> toolsRef = new AtomicReference<>();
		var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			var restTemplate = new RestTemplate();
			String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
			assertThat(response).isNotBlank();
			toolsRef.set(toolsUpdate);
		}).build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(toolsRef.get()).isNull();

		assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

		mcpServer.notifyToolsListChanged();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(toolsRef.get()).containsAll(Collections.singletonList(tool1.getTool()));
		});

		mcpServer.removeTool("tool1");

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(toolsRef.get()).isEmpty();
		});

		McpServerFeatures.SyncToolRegistration tool2 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema), request -> callResponse);

		mcpServer.addTool(tool2);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(toolsRef.get()).containsAll(Collections.singletonList(tool2.getTool()));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testInitialize() {
		var mcpServer = McpServer.sync(mcpServerTransport).build();
		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.close();
		mcpServer.close();
	}

}
