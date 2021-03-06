/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.proxy.impl;

import io.atomix.protocols.raft.protocol.CommandResponse;
import io.atomix.protocols.raft.protocol.PublishRequest;
import io.atomix.protocols.raft.protocol.QueryResponse;
import io.atomix.protocols.raft.protocol.RaftResponse;
import io.atomix.protocols.raft.service.ServiceType;
import io.atomix.protocols.raft.session.SessionId;
import org.junit.Test;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Client sequencer test.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class RaftProxySequencerTest {

  /**
   * Tests sequencing an event that arrives before a command response.
   */
  @Test
  public void testSequenceEventBeforeCommand() throws Throwable {
    RaftProxySequencer sequencer = new RaftProxySequencer(new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000));
    long sequence = sequencer.nextRequest();

    PublishRequest request = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(1)
        .withPreviousIndex(0)
        .withEvents(Collections.emptyList())
        .build();

    CommandResponse response = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(2)
        .withEventIndex(1)
        .build();

    AtomicInteger run = new AtomicInteger();
    sequencer.sequenceEvent(request, () -> assertEquals(run.getAndIncrement(), 0));
    sequencer.sequenceResponse(sequence, response, () -> assertEquals(run.getAndIncrement(), 1));
    assertEquals(run.get(), 2);
  }

  /**
   * Tests sequencing an event that arrives before a command response.
   */
  @Test
  public void testSequenceEventAfterCommand() throws Throwable {
    RaftProxySequencer sequencer = new RaftProxySequencer(new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000));
    long sequence = sequencer.nextRequest();

    PublishRequest request = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(1)
        .withPreviousIndex(0)
        .withEvents(Collections.emptyList())
        .build();

    CommandResponse response = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(2)
        .withEventIndex(1)
        .build();

    AtomicInteger run = new AtomicInteger();
    sequencer.sequenceResponse(sequence, response, () -> assertEquals(run.getAndIncrement(), 0));
    sequencer.sequenceEvent(request, () -> assertEquals(run.getAndIncrement(), 1));
    assertEquals(run.get(), 2);
  }

  /**
   * Tests sequencing an event that arrives before a command response.
   */
  @Test
  public void testSequenceEventAtCommand() throws Throwable {
    RaftProxySequencer sequencer = new RaftProxySequencer(new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000));
    long sequence = sequencer.nextRequest();

    PublishRequest request = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(2)
        .withPreviousIndex(0)
        .withEvents(Collections.emptyList())
        .build();

    CommandResponse response = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(2)
        .withEventIndex(2)
        .build();

    AtomicInteger run = new AtomicInteger();
    sequencer.sequenceResponse(sequence, response, () -> assertEquals(run.getAndIncrement(), 1));
    sequencer.sequenceEvent(request, () -> assertEquals(run.getAndIncrement(), 0));
    assertEquals(run.get(), 2);
  }

  /**
   * Tests sequencing an event that arrives before a command response.
   */
  @Test
  public void testSequenceEventAfterAllCommands() throws Throwable {
    RaftProxySequencer sequencer = new RaftProxySequencer(new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000));
    long sequence = sequencer.nextRequest();

    PublishRequest request1 = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(2)
        .withPreviousIndex(0)
        .withEvents(Collections.emptyList())
        .build();

    PublishRequest request2 = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(3)
        .withPreviousIndex(2)
        .withEvents(Collections.emptyList())
        .build();

    CommandResponse response = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(2)
        .withEventIndex(2)
        .build();

    AtomicInteger run = new AtomicInteger();
    sequencer.sequenceEvent(request1, () -> assertEquals(run.getAndIncrement(), 0));
    sequencer.sequenceEvent(request2, () -> assertEquals(run.getAndIncrement(), 2));
    sequencer.sequenceResponse(sequence, response, () -> assertEquals(run.getAndIncrement(), 1));
    assertEquals(run.get(), 3);
  }

  /**
   * Tests sequencing an event that arrives before a command response.
   */
  @Test
  public void testSequenceEventAbsentCommand() throws Throwable {
    RaftProxySequencer sequencer = new RaftProxySequencer(new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000));

    PublishRequest request1 = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(2)
        .withPreviousIndex(0)
        .withEvents(Collections.emptyList())
        .build();

    PublishRequest request2 = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(3)
        .withPreviousIndex(2)
        .withEvents(Collections.emptyList())
        .build();

    AtomicInteger run = new AtomicInteger();
    sequencer.sequenceEvent(request1, () -> assertEquals(run.getAndIncrement(), 0));
    sequencer.sequenceEvent(request2, () -> assertEquals(run.getAndIncrement(), 1));
    assertEquals(run.get(), 2);
  }

  /**
   * Tests sequencing callbacks with the sequencer.
   */
  @Test
  public void testSequenceResponses() throws Throwable {
    RaftProxySequencer sequencer = new RaftProxySequencer(new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000));
    long sequence1 = sequencer.nextRequest();
    long sequence2 = sequencer.nextRequest();
    assertTrue(sequence2 == sequence1 + 1);

    CommandResponse commandResponse = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(2)
        .withEventIndex(0)
        .build();

    QueryResponse queryResponse = QueryResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(2)
        .withEventIndex(0)
        .build();

    AtomicBoolean run = new AtomicBoolean();
    sequencer.sequenceResponse(sequence2, queryResponse, () -> run.set(true));
    sequencer.sequenceResponse(sequence1, commandResponse, () -> assertFalse(run.get()));
    assertTrue(run.get());
  }

  /**
   * Tests sequencing responses with a missing PublishRequest.
   */
  @Test
  public void testSequenceMissingEvent() throws Throwable {
    RaftProxyState state = new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000);
    state.setCommandRequest(2);
    state.setResponseIndex(15);
    state.setEventIndex(5);

    AtomicInteger run = new AtomicInteger();

    RaftProxySequencer sequencer = new RaftProxySequencer(state);
    sequencer.requestSequence = 2;
    sequencer.responseSequence = 1;
    sequencer.eventIndex = 5;

    CommandResponse commandResponse = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(20)
        .withEventIndex(10)
        .build();
    sequencer.sequenceResponse(2, commandResponse, () -> assertEquals(run.getAndIncrement(), 0));

    PublishRequest publishRequest = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(25)
        .withPreviousIndex(5)
        .withEvents(Collections.emptyList())
        .build();
    sequencer.sequenceEvent(publishRequest, () -> assertEquals(run.getAndIncrement(), 1));

    assertEquals(run.get(), 2);
  }

  /**
   * Tests sequencing multiple responses that indicate missing events.
   */
  @Test
  public void testSequenceMultipleMissingEvents() throws Throwable {
    RaftProxyState state = new RaftProxyState("test", SessionId.from(1), UUID.randomUUID().toString(), ServiceType.from("test"), 1000);
    state.setCommandRequest(2);
    state.setResponseIndex(15);
    state.setEventIndex(5);

    AtomicInteger run = new AtomicInteger();

    RaftProxySequencer sequencer = new RaftProxySequencer(state);
    sequencer.requestSequence = 3;
    sequencer.responseSequence = 1;
    sequencer.eventIndex = 5;

    CommandResponse commandResponse2 = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(20)
        .withEventIndex(10)
        .build();
    sequencer.sequenceResponse(3, commandResponse2, () -> assertEquals(run.getAndIncrement(), 1));

    CommandResponse commandResponse1 = CommandResponse.newBuilder()
        .withStatus(RaftResponse.Status.OK)
        .withIndex(18)
        .withEventIndex(8)
        .build();
    sequencer.sequenceResponse(2, commandResponse1, () -> assertEquals(run.getAndIncrement(), 0));

    PublishRequest publishRequest1 = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(25)
        .withPreviousIndex(5)
        .withEvents(Collections.emptyList())
        .build();
    sequencer.sequenceEvent(publishRequest1, () -> assertEquals(run.getAndIncrement(), 2));

    PublishRequest publishRequest2 = PublishRequest.newBuilder()
        .withSession(1)
        .withEventIndex(28)
        .withPreviousIndex(8)
        .withEvents(Collections.emptyList())
        .build();
    sequencer.sequenceEvent(publishRequest2, () -> assertEquals(run.getAndIncrement(), 3));

    assertEquals(run.get(), 4);
  }

}
