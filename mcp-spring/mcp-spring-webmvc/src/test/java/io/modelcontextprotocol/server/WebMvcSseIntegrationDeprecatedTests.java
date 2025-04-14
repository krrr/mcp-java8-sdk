/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransport;
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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Deprecated
public class WebMvcSseIntegrationDeprecatedTests {

	private static final int PORT = 8183;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcSseServerTransport mcpServerTransport;

	McpClient.SyncSpec clientBuilder;

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransport webMvcSseServerTransport() {
			return new WebMvcSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransport transport) {
			return transport.getRouterFunction();
		}

	}

	private Tomcat tomcat;

	private AnnotationConfigWebApplicationContext appContext;

	@BeforeEach
	public void before() {

		// Set up Tomcat first
		tomcat = new Tomcat();
		tomcat.setPort(PORT);

		// Set Tomcat base directory to java.io.tmpdir to avoid permission issues
		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		// Use the same directory for document base
		Context context = tomcat.addContext("", baseDir);

		// Create and configure Spring WebMvc context
		appContext = new AnnotationConfigWebApplicationContext();
		appContext.register(TestConfig.class);
		appContext.setServletContext(context.getServletContext());
		appContext.refresh();

		// Get the transport from Spring context
		mcpServerTransport = appContext.getBean(WebMvcSseServerTransport.class);

		// Create DispatcherServlet with our Spring context
		DispatcherServlet dispatcherServlet = new DispatcherServlet(appContext);
		// dispatcherServlet.setThrowExceptionIfNoHandlerFound(true);

		// Add servlet to Tomcat and get the wrapper
		var wrapper = Tomcat.addServlet(context, "dispatcherServlet", dispatcherServlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addServletMappingDecoded("/*", "dispatcherServlet");

		try {
			// Configure and start the connector with async support
			var connector = tomcat.getConnector();
			connector.setAsyncTimeout(3000); // 3 seconds timeout for async requests
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
		if (appContext != null) {
			appContext.close();
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

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
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

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build();

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
	void testCreateMessageSuccess() throws InterruptedException {

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

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
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

		// Remove a root
		mcpClient.removeRoot(roots.get(0).getUri());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Collections.singletonList(roots.get(1)));
		});

		// Add a new root
		var root3 = new Root("uri3://", "root3");
		mcpClient.addRoot(root3);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Arrays.asList(roots.get(1), root3));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsWithoutCapability() {
		var mcpServer = McpServer.sync(mcpServerTransport).rootsChangeConsumer(rootsUpdate -> {
		}).build();

		// Create client without roots capability
		// No roots capability
		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		// Attempt to list roots should fail
		assertThatThrownBy(() -> mcpServer.listRoots().getRoots()).isInstanceOf(McpError.class)
			.hasMessage("Roots not supported");

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsWithEmptyRootsList() {
		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(Collections.emptyList()) // Empty roots list
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).isEmpty();
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsWithMultipleConsumers() {
		List<Root> roots = Collections.singletonList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef1.set(rootsUpdate))
			.rootsChangeConsumer(rootsUpdate -> rootsRef2.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef1.get()).containsAll(roots);
			assertThat(rootsRef2.get()).containsAll(roots);
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsServerCloseWithActiveSubscription() {
		List<Root> roots = Collections.singletonList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		// Close server while subscription is active
		mcpServer.close();

		// Verify client can handle server closure gracefully
		mcpClient.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

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
					// perform a blocking call to a remote service
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
					// perform a blocking call to a remote service
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();
		var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			var restTemplate = new RestTemplate();
			String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
			assertThat(response).isNotBlank();
			rootsRef.set(toolsUpdate);
		}).build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

		mcpServer.notifyToolsListChanged();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Collections.singletonList(tool1.getTool()));
		});

		// Remove a tool
		mcpServer.removeTool("tool1");

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).isEmpty();
		});

		// Add a new tool
		McpServerFeatures.SyncToolRegistration tool2 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema), request -> callResponse);

		mcpServer.addTool(tool2);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Collections.singletonList(tool2.getTool()));
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
