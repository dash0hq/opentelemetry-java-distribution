/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Serves a few HTTP requests to itself, then exits.
 *
 * <p>The application contains no OpenTelemetry code at all, which is the point: every span, metric
 * and log record it produces comes from the agent attached with {@code -javaagent:}. It is written
 * against the JDK alone so the example needs no build tool and no dependency resolution, which
 * keeps what is being demonstrated to the distribution rather than to a framework.
 *
 * <p>Four kinds of telemetry come out of this, each from a different upstream instrumentation:
 * server spans from {@code com.sun.net.httpserver}, client spans from {@link HttpURLConnection},
 * JVM metrics from the runtime telemetry instrumentation, and log records from {@link Logger}.
 *
 * <p>It drives its own traffic and exits rather than serving indefinitely. A long-running server
 * with a separate traffic generator would have to solve a readiness race before it could assert
 * anything, and process exit gives the agent a clean flush and the test a definite finishing
 * point.
 *
 * <p>The request-handling executor is shut down explicitly. {@code HttpServer.stop} does not touch
 * an executor the caller supplied, and its threads are not daemon threads, so without this the JVM
 * never exits and the example hangs after producing all of its telemetry.
 */
public final class DemoApplication {

  private static final Logger logger = Logger.getLogger(DemoApplication.class.getName());

  private static final int REQUESTS = 5;

  /**
   * Long enough for at least one periodic metric export at the interval the compose file sets,
   * so the metrics assertions have something to read.
   */
  private static final long METRIC_SETTLE_MILLIS = 3000;

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/items",
        exchange -> {
          byte[] body = ("{\"item\":\"" + exchange.getRequestURI().getPath() + "\"}")
              .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    ExecutorService requestHandlers = Executors.newFixedThreadPool(2);
    server.setExecutor(requestHandlers);
    server.start();

    int port = server.getAddress().getPort();
    logger.info("demo application listening on port " + port);

    for (int item = 0; item < REQUESTS; item++) {
      int status = get("http://127.0.0.1:" + port + "/items/" + item);
      logger.info("fetched item " + item + " with status " + status);
    }

    Thread.sleep(METRIC_SETTLE_MILLIS);

    server.stop(0);
    requestHandlers.shutdown();
    requestHandlers.awaitTermination(10, TimeUnit.SECONDS);
    logger.info("demo application finished");
  }

  private static int get(String url) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setRequestMethod("GET");
    try (InputStream in = connection.getInputStream()) {
      while (in.read() != -1) {
        continue;
      }
    }
    int status = connection.getResponseCode();
    connection.disconnect();
    return status;
  }
}
