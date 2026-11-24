/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A minimal application for the agent to attach to. It must not depend on the OpenTelemetry API.
 *
 * <p>With the system property {@value #SEND_REQUEST} set to {@code true}, it sends one HTTP request
 * to a socket it opens itself and prints each header that request carried, prefixed with {@value
 * #RECEIVED_HEADER}.
 *
 * @param args optionally one value, the number of milliseconds to stay alive before exiting
 */
public final class TestApplication {

  /** Printed as the last thing {@code main} does, and nowhere else. */
  public static final String COMPLETION_MARKER = "test-application-completed";

  public static final String SEND_REQUEST = "dash0.test.send.request";
  public static final String RECEIVED_HEADER = "received-header: ";

  private TestApplication() {}

  public static void main(String[] args) throws Exception {
    if (Boolean.getBoolean(SEND_REQUEST)) {
      sendRequestAndPrintItsHeaders();
    }
    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println(COMPLETION_MARKER);
  }

  /** Uses a plain socket as the server, which the agent does not instrument. */
  private static void sendRequestAndPrintItsHeaders() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread client =
          new Thread(
              () -> {
                try {
                  HttpURLConnection connection =
                      (HttpURLConnection)
                          new URL("http://127.0.0.1:" + server.getLocalPort() + "/")
                              .openConnection();
                  connection.getResponseCode();
                  connection.disconnect();
                } catch (IOException e) {
                  throw new IllegalStateException(e);
                }
              });
      client.start();
      try (Socket socket = server.accept()) {
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        reader.readLine();
        for (String line = reader.readLine();
            line != null && !line.isEmpty();
            line = reader.readLine()) {
          System.out.println(RECEIVED_HEADER + line);
        }
        OutputStream out = socket.getOutputStream();
        out.write(
            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
      }
      try {
        client.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
