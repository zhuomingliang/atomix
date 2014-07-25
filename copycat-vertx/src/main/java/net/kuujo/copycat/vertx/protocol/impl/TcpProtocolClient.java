/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.vertx.protocol.impl;

import java.util.HashMap;
import java.util.Map;

import net.kuujo.copycat.log.Entry;
import net.kuujo.copycat.protocol.InstallRequest;
import net.kuujo.copycat.protocol.InstallResponse;
import net.kuujo.copycat.protocol.PingRequest;
import net.kuujo.copycat.protocol.PingResponse;
import net.kuujo.copycat.protocol.PollRequest;
import net.kuujo.copycat.protocol.PollResponse;
import net.kuujo.copycat.protocol.ProtocolClient;
import net.kuujo.copycat.protocol.ProtocolException;
import net.kuujo.copycat.protocol.Response;
import net.kuujo.copycat.protocol.SubmitRequest;
import net.kuujo.copycat.protocol.SubmitResponse;
import net.kuujo.copycat.protocol.SyncRequest;
import net.kuujo.copycat.protocol.SyncResponse;
import net.kuujo.copycat.serializer.Serializer;
import net.kuujo.copycat.serializer.SerializerFactory;
import net.kuujo.copycat.util.AsyncCallback;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.net.NetClient;
import org.vertx.java.core.net.NetSocket;
import org.vertx.java.core.parsetools.RecordParser;

/**
 * Vert.x TCP protocol client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class TcpProtocolClient implements ProtocolClient {
  private static final Serializer serializer = SerializerFactory.getSerializer();
  private final Vertx vertx;
  private final String host;
  private final int port;
  private NetClient client;
  private NetSocket socket;
  private long id;
  private final Map<Long, ResponseHolder<?>> responses = new HashMap<>();

  /**
   * Holder for response handlers.
   */
  private static class ResponseHolder<T extends Response> {
    private final AsyncCallback<T> callback;
    private final ResponseType type;
    private final long timer;
    private ResponseHolder(long timerId, ResponseType type, AsyncCallback<T> callback) {
      this.timer = timerId;
      this.type = type;
      this.callback = callback;
    }
  }

  /**
   * Indicates response types.
   */
  private static enum ResponseType {
    PING,
    SYNC,
    INSTALL,
    POLL,
    SUBMIT;
  }

  public TcpProtocolClient(Vertx vertx, String host, int port) {
    this.vertx = vertx;
    this.host = host;
    this.port = port;
  }

  @Override
  public void ping(PingRequest request, AsyncCallback<PingResponse> callback) {
    if (socket != null) {
      long requestId = ++id;
      socket.write(new JsonObject().putString("type", "ping")
          .putNumber("id", requestId)
          .putNumber("term", request.term())
          .putString("leader", request.leader()).encode() + '\00');
      storeCallback(requestId, ResponseType.PING, callback);
    } else {
      callback.fail(new ProtocolException("Client not connected"));
    }
  }

  @Override
  public void sync(SyncRequest request, AsyncCallback<SyncResponse> callback) {
    if (socket != null) {
      long requestId = ++id;
      JsonArray jsonEntries = new JsonArray();
      for (Entry entry : request.entries()) {
        jsonEntries.addBinary(serializer.writeValue(entry));
      }
      socket.write(new JsonObject().putString("type", "sync")
          .putNumber("id", requestId)
          .putNumber("term", request.term())
          .putString("leader", request.leader())
          .putNumber("prevIndex", request.prevLogIndex())
          .putNumber("prevTerm", request.prevLogTerm())
          .putArray("entries", jsonEntries)
          .putNumber("commit", request.commit()).encode() + '\00');
      storeCallback(requestId, ResponseType.SYNC, callback);
    } else {
      callback.fail(new ProtocolException("Client not connected"));
    }
  }

  @Override
  public void install(InstallRequest request, AsyncCallback<InstallResponse> callback) {
    if (socket != null) {
      long requestId = ++id;
      JsonArray jsonCluster = new JsonArray();
      for (String member : request.cluster()) {
        jsonCluster.addString(member);
      }
      socket.write(new JsonObject().putString("type", "install")
          .putNumber("id", requestId)
          .putNumber("term", request.term())
          .putString("leader", request.leader())
          .putArray("cluster", jsonCluster)
          .putBinary("data", request.data())
          .putBoolean("complete", request.complete())
          .encode() + '\00');
      storeCallback(requestId, ResponseType.INSTALL, callback);
    } else {
      callback.fail(new ProtocolException("Client not connected"));
    }
  }

  @Override
  public void poll(PollRequest request, AsyncCallback<PollResponse> callback) {
    if (socket != null) {
      long requestId = ++id;
      socket.write(new JsonObject().putString("type", "poll")
          .putNumber("id", requestId)
          .putNumber("term", request.term())
          .putString("candidate", request.candidate())
          .putNumber("lastIndex", request.lastLogIndex())
          .putNumber("lastTerm", request.lastLogTerm())
          .encode() + '\00');
      storeCallback(requestId, ResponseType.POLL, callback);
    } else {
      callback.fail(new ProtocolException("Client not connected"));
    }
  }

  @Override
  public void submit(SubmitRequest request, AsyncCallback<SubmitResponse> callback) {
    if (socket != null) {
      long requestId = ++id;
      socket.write(new JsonObject().putString("type", "submit")
          .putString("command", request.command())
          .putObject("args", new JsonObject(request.args()))
          .encode() + '\00');
      storeCallback(requestId, ResponseType.SUBMIT, callback);
    } else {
      callback.fail(new ProtocolException("Client not connected"));
    }
  }

  /**
   * Handles an identifiable response.
   */
  @SuppressWarnings("unchecked")
  private void handleResponse(long id, JsonObject response) {
    ResponseHolder<?> holder = responses.remove(id);
    if (holder != null) {
      vertx.cancelTimer(holder.timer);
      switch (holder.type) {
        case PING:
          handlePingResponse(response, (AsyncCallback<PingResponse>) holder.callback);
          break;
        case SYNC:
          handleSyncResponse(response, (AsyncCallback<SyncResponse>) holder.callback);
          break;
        case INSTALL:
          handleInstallResponse(response, (AsyncCallback<InstallResponse>) holder.callback);
          break;
        case POLL:
          handlePollResponse(response, (AsyncCallback<PollResponse>) holder.callback);
          break;
        case SUBMIT:
          handleSubmitResponse(response, (AsyncCallback<SubmitResponse>) holder.callback);
          break;
      }
    }
  }

  /**
   * Handles a ping response.
   */
  private void handlePingResponse(JsonObject response, AsyncCallback<PingResponse> callback) {
    String status = response.getString("status");
    if (status == null) {
      callback.fail(new ProtocolException("Invalid response"));
    } else if (status.equals("ok")) {
      callback.complete(new PingResponse(response.getLong("term")));
    } else if (status.equals("error")) {
      callback.fail(new ProtocolException(response.getString("message")));
    }
  }

  /**
   * Handles a sync response.
   */
  private void handleSyncResponse(JsonObject response, AsyncCallback<SyncResponse> callback) {
    String status = response.getString("status");
    if (status == null) {
      callback.fail(new ProtocolException("Invalid response"));
    } else if (status.equals("ok")) {
      callback.complete(new SyncResponse(response.getLong("term"), response.getBoolean("succeeded")));
    } else if (status.equals("error")) {
      callback.fail(new ProtocolException(response.getString("message")));
    }
  }

  /**
   * Handles an install response.
   */
  private void handleInstallResponse(JsonObject response, AsyncCallback<InstallResponse> callback) {
    String status = response.getString("status");
    if (status == null) {
      callback.fail(new ProtocolException("Invalid response"));
    } else if (status.equals("ok")) {
      callback.complete(new InstallResponse(response.getLong("term"), response.getBoolean("succeeded")));
    } else if (status.equals("error")) {
      callback.fail(new ProtocolException(response.getString("message")));
    }
  }

  /**
   * Handles a poll response.
   */
  private void handlePollResponse(JsonObject response, AsyncCallback<PollResponse> callback) {
    String status = response.getString("status");
    if (status == null) {
      callback.fail(new ProtocolException("Invalid response"));
    } else if (status.equals("ok")) {
      callback.complete(new PollResponse(response.getLong("term"), response.getBoolean("voteGranted")));
    } else if (status.equals("error")) {
      callback.fail(new ProtocolException(response.getString("message")));
    }
  }

  /**
   * Handles a submit response.
   */
  private void handleSubmitResponse(JsonObject response, AsyncCallback<SubmitResponse> callback) {
    String status = response.getString("status");
    if (status == null) {
      callback.fail(new ProtocolException("Invalid response"));
    } else if (status.equals("ok")) {
      callback.complete(new SubmitResponse(response.getObject("result").toMap()));
    } else if (status.equals("error")) {
      callback.fail(new ProtocolException(response.getString("message")));
    }
  }

  /**
   * Stores a response callback by ID.
   */
  private <T extends Response> void storeCallback(final long id, ResponseType responseType, AsyncCallback<T> callback) {
    long timerId = vertx.setTimer(30000, new Handler<Long>() {
      @Override
      public void handle(Long timerID) {
        ResponseHolder<?> holder = responses.remove(id);
        if (holder != null) {
          holder.callback.fail(new ProtocolException("Request timed out"));
        }
      }
    });
    ResponseHolder<T> holder = new ResponseHolder<T>(timerId, responseType, callback);
    responses.put(id, holder);
  }

  @Override
  public void connect(final AsyncCallback<Void> callback) {
    if (client == null) {
      client = vertx.createNetClient();
      client.connect(port, host, new Handler<AsyncResult<NetSocket>>() {
        @Override
        public void handle(AsyncResult<NetSocket> result) {
          if (result.failed()) {
            callback.fail(result.cause());
          } else {
            socket = result.result();
            socket.dataHandler(RecordParser.newDelimited(new byte[]{'\00'}, new Handler<Buffer>() {
              @Override
              public void handle(Buffer buffer) {
                JsonObject response = new JsonObject(buffer.toString());
                long id = response.getLong("id");
                handleResponse(id, response);
              }
            }));
            callback.complete(null);
          }
        }
      });
    } else {
      callback.complete(null);
    }
  }

  @Override
  public void close(final AsyncCallback<Void> callback) {
    if (client != null && socket != null) {
      socket.closeHandler(new Handler<Void>() {
        @Override
        public void handle(Void event) {
          socket = null;
          client.close();
          client = null;
          callback.complete(null);
        }
      }).close();
    } else if (client != null) {
      client.close();
      client = null;
      callback.complete(null);
    } else {
      callback.complete(null);
    }
  }

}