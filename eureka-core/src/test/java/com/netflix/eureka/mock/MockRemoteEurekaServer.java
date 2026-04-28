package com.netflix.eureka.mock;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.netflix.appinfo.AbstractEurekaIdentity;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.converters.jackson.EurekaJsonJacksonCodec;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.*;
import org.junit.Assert;
import org.junit.rules.ExternalResource;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Nitesh Kant
 */
public class MockRemoteEurekaServer extends ExternalResource {

    public static final String EUREKA_API_BASE_PATH = "/eureka/v2/";

    private final Map<String, Application> applicationMap;
    private final Map<String, Application> applicationDeltaMap;
    private final Server server;
    private boolean sentDelta;
    private int port;
    private volatile boolean simulateNotReady;

    public MockRemoteEurekaServer(int port, Map<String, Application> applicationMap,
                                  Map<String, Application> applicationDeltaMap) {
        this.applicationMap = applicationMap;
        this.applicationDeltaMap = applicationDeltaMap;
        EurekaServerConfig serverConfig = new DefaultEurekaServerConfig();
        EurekaServerContext serverContext = mock(EurekaServerContext.class);
        when(serverContext.getServerConfig()).thenReturn(serverConfig);

        AppsResourceHandler handler = new AppsResourceHandler();
        handler.addFilter(new ServerRequestAuthFilter(serverContext));
        handler.addFilter(new RateLimitingFilter(serverContext));
        server = new Server(port);
        server.setHandler(handler);
        System.out.println(String.format(
                "Created eureka server mock with applications map %s and applications delta map %s",
                stringifyAppMap(applicationMap), stringifyAppMap(applicationDeltaMap)));
    }

    @Override
    protected void before() throws Throwable {
        start();
    }

    @Override
    protected void after() {
        try {
            stop();
        } catch (Exception e) {
            Assert.fail(e.getMessage());
        }
    }

    public void start() throws Exception {
        server.start();
        port = ((NetworkConnector) server.getConnectors()[0]).getLocalPort();
    }

    public void stop() throws Exception {
        server.stop();
    }

    public boolean isSentDelta() {
        return sentDelta;
    }

    public int getPort() {
        return port;
    }

    public void simulateNotReady(boolean simulateNotReady) {
        this.simulateNotReady = simulateNotReady;
    }

    private static String stringifyAppMap(Map<String, Application> applicationMap) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Application> entry : applicationMap.entrySet()) {
            String entryAsString = String.format("{ name : %s , instance count: %d }", entry.getKey(),
                    entry.getValue().getInstances().size());
            builder.append(entryAsString);
        }
        return builder.toString();
    }

    private class AppsResourceHandler extends AbstractHandler {

        private final List<Filter> filters = new ArrayList<>();

        void addFilter(Filter filter) {
            filters.add(filter);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {

            if (simulateNotReady) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                baseRequest.setHandled(true);
                return;
            }
            String authName = request.getHeader(AbstractEurekaIdentity.AUTH_NAME_HEADER_KEY);
            String authVersion = request.getHeader(AbstractEurekaIdentity.AUTH_VERSION_HEADER_KEY);
            String authId = request.getHeader(AbstractEurekaIdentity.AUTH_ID_HEADER_KEY);

            Assert.assertNotNull(authName);
            Assert.assertNotNull(authVersion);
            Assert.assertNotNull(authId);

            Assert.assertTrue(!authName.equals(ServerRequestAuthFilter.UNKNOWN));
            Assert.assertTrue(!authVersion.equals(ServerRequestAuthFilter.UNKNOWN));
            Assert.assertTrue(!authId.equals(ServerRequestAuthFilter.UNKNOWN));

            for (Filter filter : filters) {
                filter.doFilter(request, response, new FilterChain() {
                    @Override
                    public void doFilter(ServletRequest request, ServletResponse response)
                            throws IOException, ServletException {
                        // do nothing;
                    }
                });
            }

            String pathInfo = request.getPathInfo();
            System.out.println(
                    "Eureka resource mock, received request on path: " + pathInfo + ". HTTP method: |" + request
                            .getMethod() + '|');
            boolean handled = false;
            if (null != pathInfo && pathInfo.startsWith("")) {
                pathInfo = pathInfo.substring(EUREKA_API_BASE_PATH.length());
                if (pathInfo.startsWith("apps/delta")) {
                    Applications apps = new Applications();
                    for (Application application : applicationDeltaMap.values()) {
                        apps.addApplication(application);
                    }
                    apps.setAppsHashCode(apps.getReconcileHashCode());
                    sendOkResponseWithContent(baseRequest, response, toJson(apps));
                    handled = true;
                    sentDelta = true;
                } else if (request.getMethod().equals("PUT") && pathInfo.startsWith("apps")) {
                    InstanceInfo instanceInfo = InstanceInfo.Builder.newBuilder()
                        .setAppName("TEST-APP").build();
                    sendOkResponseWithContent(baseRequest, response,
                        new EurekaJsonJacksonCodec().getObjectMapper(Applications.class).writeValueAsString(instanceInfo));
                    handled = true;
                } else if (pathInfo.startsWith("apps")) {
                    Applications apps = new Applications();
                    for (Application application : applicationMap.values()) {
                        apps.addApplication(application);
                    }
                    apps.setAppsHashCode(apps.getReconcileHashCode());
                    sendOkResponseWithContent(baseRequest, response, toJson(apps));
                    handled = true;
                }
            }

            if (!handled) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "Request path: " + pathInfo + " not supported by eureka resource mock.");
                baseRequest.setHandled(true);
            }
        }

        private void sendOkResponseWithContent(Request baseRequest, HttpServletResponse response, String content)
                throws IOException {
            response.setContentType("application/json; charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().write(content.getBytes("UTF-8"));
            response.getOutputStream().flush();
            baseRequest.setHandled(true);
            System.out.println("Eureka resource mock, sent response for request path: " + baseRequest.getPathInfo() +
                    " with content" + content);
        }
    }

    private String toJson(Applications apps) throws IOException {
        return new EurekaJsonJacksonCodec().getObjectMapper(Applications.class).writeValueAsString(apps);
    }

}
