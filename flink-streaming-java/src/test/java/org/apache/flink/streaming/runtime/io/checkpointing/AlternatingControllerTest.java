/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io.checkpointing;

import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.CheckpointType;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.checkpoint.channel.RecordingChannelStateWriter;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.event.RuntimeEvent;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.EventAnnouncement;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGateBuilder;
import org.apache.flink.runtime.io.network.partition.consumer.TestInputChannel;
import org.apache.flink.streaming.api.operators.SyncMailboxExecutor;
import org.apache.flink.streaming.runtime.tasks.TestSubtaskCheckpointCoordinator;
import org.apache.flink.streaming.util.TestCheckpointedInputGateBuilder;
import org.apache.flink.util.clock.ManualClock;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static junit.framework.TestCase.assertTrue;
import static org.apache.flink.runtime.checkpoint.CheckpointOptions.alignedNoTimeout;
import static org.apache.flink.runtime.checkpoint.CheckpointOptions.alignedWithTimeout;
import static org.apache.flink.runtime.checkpoint.CheckpointOptions.unaligned;
import static org.apache.flink.runtime.checkpoint.CheckpointType.CHECKPOINT;
import static org.apache.flink.runtime.checkpoint.CheckpointType.SAVEPOINT;
import static org.apache.flink.runtime.io.network.api.serialization.EventSerializer.toBuffer;
import static org.apache.flink.runtime.io.network.util.TestBufferFactory.createBuffer;
import static org.apache.flink.runtime.state.CheckpointStorageLocationReference.getDefault;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** {@link AlternatingController} test. */
public class AlternatingControllerTest {

    private static final ManualClock clock = new ManualClock(1);

    @Test
    public void testChannelUnblockedAfterDifferentBarriers() throws Exception {
        CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                3,
                                TestBarrierHandlerFactory.forTarget(
                                                new ValidatingCheckpointHandler())
                                        .withClock(clock))
                        .build();
        long barrierId = 1L;
        long ts = clock.relativeTimeNanos();
        long timeout = 10;

        send(barrier(barrierId, ts, unaligned(getDefault())), 0, gate);

        TestInputChannel acChannel = (TestInputChannel) gate.getChannel(1);
        acChannel.setBlocked(true);
        send(
                barrier(barrierId, ts, alignedWithTimeout(getDefault(), Integer.MAX_VALUE)),
                acChannel.getChannelIndex(),
                gate);
        assertFalse(acChannel.isBlocked());

        clock.advanceTime(timeout, TimeUnit.MILLISECONDS);
        TestInputChannel acChannelWithTimeout = (TestInputChannel) gate.getChannel(2);
        acChannelWithTimeout.setBlocked(true);
        send(
                barrier(barrierId, ts, alignedWithTimeout(getDefault(), timeout)),
                acChannelWithTimeout.getChannelIndex(),
                gate);
        assertFalse(acChannelWithTimeout.isBlocked());
    }

    /**
     * Upon subsuming (or canceling) a checkpoint, channels should be notified regardless of whether
     * UC controller is currently being used or not. Otherwise, channels may not capture in-flight
     * buffers.
     */
    @Test
    public void testChannelResetOnNewBarrier() throws Exception {
        RecordingChannelStateWriter stateWriter = new RecordingChannelStateWriter();
        try (CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                2,
                                TestBarrierHandlerFactory.forTarget(
                                                new ValidatingCheckpointHandler())
                                        .withClock(clock))
                        .withChannelStateWriter(stateWriter)
                        .withRemoteChannels()
                        .withMailboxExecutor()
                        .build()) {

            sendBarrier(
                    0,
                    clock.relativeTimeMillis(),
                    SAVEPOINT,
                    gate,
                    0); // using AC because UC would require ordering in gate while polling
            ((RemoteInputChannel) gate.getChannel(0))
                    .onBuffer(createBuffer(1024), 1, 0); // to be captured
            send(
                    toBuffer(
                            new CheckpointBarrier(
                                    1, clock.relativeTimeMillis(), unaligned(getDefault())),
                            true),
                    1,
                    gate);

            assertFalse(stateWriter.getAddedInput().isEmpty());
        }
    }

    /**
     * If a checkpoint announcement was processed from one channel and then UC-barrier arrives on
     * another channel, this UC barrier should be processed by the UC controller.
     */
    @Test
    public void testSwitchToUnalignedByUpstream() throws Exception {
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        try (CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                2, TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .build()) {

            CheckpointBarrier aligned =
                    new CheckpointBarrier(
                            1,
                            clock.relativeTimeMillis(),
                            alignedWithTimeout(getDefault(), Integer.MAX_VALUE));

            send(
                    toBuffer(new EventAnnouncement(aligned, 0), true),
                    0,
                    gate); // process announcement but not the barrier
            assertEquals(0, target.triggeredCheckpointCounter);
            send(
                    toBuffer(aligned.asUnaligned(), true),
                    1,
                    gate); // pretend it came from upstream before the first (AC) barrier was picked
            // up
            assertEquals(1, target.triggeredCheckpointCounter);
        }
    }

    @Test
    public void testCheckpointHandling() throws Exception {
        testBarrierHandling(CHECKPOINT);
    }

    @Test
    public void testSavepointHandling() throws Exception {
        testBarrierHandling(SAVEPOINT);
    }

    @Test
    public void testAlternation() throws Exception {
        int numBarriers = 123;
        int numChannels = 123;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        try (CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                numChannels,
                                TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .build()) {

            List<Long> barriers = new ArrayList<>();
            for (long barrier = 0; barrier < numBarriers; barrier++) {
                barriers.add(barrier);
                CheckpointType type = barrier % 2 == 0 ? CHECKPOINT : SAVEPOINT;
                for (int channel = 0; channel < numChannels; channel++) {
                    send(
                            barrier(
                                            barrier,
                                            clock.relativeTimeMillis(),
                                            alignedNoTimeout(type, getDefault()))
                                    .retainBuffer(),
                            channel,
                            gate);
                }
            }
            assertEquals(barriers, target.triggeredCheckpoints);
        }
    }

    @Test
    public void testAlignedNeverTimeoutableCheckpoint() throws Exception {
        int numChannels = 2;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        try (CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                numChannels,
                                TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .build()) {

            Buffer neverTimeoutableCheckpoint = withTimeout(Integer.MAX_VALUE);
            send(neverTimeoutableCheckpoint, 0, gate);
            sendData(1000, 1, gate);
            assertEquals(0, target.getTriggeredCheckpointCounter());

            send(neverTimeoutableCheckpoint, 1, gate);
            assertEquals(1, target.getTriggeredCheckpointCounter());
        }
    }

    @Test
    public void testTimeoutAlignment() throws Exception {
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        try (CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                2, TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .withRemoteChannels()
                        .withMailboxExecutor()
                        .build()) {
            testTimeoutBarrierOnTwoChannels(target, gate);
        }
    }

    @Test
    public void testTimeoutAlignmentAfterProcessingBarrier() throws Exception {
        int numChannels = 3;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        try (CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                numChannels,
                                TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .withRemoteChannels()
                        .withMailboxExecutor()
                        .build()) {

            send(
                    barrier(
                            1,
                            clock.relativeTimeMillis(),
                            alignedWithTimeout(getDefault(), Integer.MAX_VALUE)),
                    2,
                    gate);

            assertEquals(0, target.getTriggeredCheckpointCounter());

            testTimeoutBarrierOnTwoChannels(target, gate);
        }
    }

    private void testTimeoutBarrierOnTwoChannels(
            ValidatingCheckpointHandler target, CheckpointedInputGate gate) throws Exception {
        long alignmentTimeout = 10;
        Buffer checkpointBarrier = withTimeout(alignmentTimeout);

        getChannel(gate, 0).onBuffer(dataBuffer(), 0, 0);
        getChannel(gate, 0).onBuffer(dataBuffer(), 1, 0);
        getChannel(gate, 0).onBuffer(checkpointBarrier.retainBuffer(), 2, 0);
        getChannel(gate, 1).onBuffer(dataBuffer(), 0, 0);
        getChannel(gate, 1).onBuffer(checkpointBarrier.retainBuffer(), 1, 0);

        assertEquals(0, target.getTriggeredCheckpointCounter());
        assertAnnouncement(gate);
        clock.advanceTime(alignmentTimeout * 2, TimeUnit.MILLISECONDS);
        assertAnnouncement(gate);
        assertBarrier(gate);
        assertBarrier(gate);
        assertEquals(1, target.getTriggeredCheckpointCounter());
        assertThat(target.getTriggeredCheckpointOptions(), contains(unaligned(getDefault())));
        // Followed by overtaken buffers
        assertData(gate);
        assertData(gate);
        assertData(gate);
    }

    private Buffer dataBuffer() {
        return createBuffer(100).retainBuffer();
    }

    /**
     * This test tries to make sure that the first time out happens after processing {@link
     * EventAnnouncement} but before/during processing the first {@link CheckpointBarrier}.
     */
    @Test
    public void testTimeoutAlignmentOnFirstBarrier() throws Exception {
        int numChannels = 2;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                numChannels,
                                TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .withRemoteChannels()
                        .withMailboxExecutor()
                        .build();

        long alignmentTimeout = 100;
        Buffer checkpointBarrier = withTimeout(alignmentTimeout);

        for (int i = 0; i < numChannels; i++) {
            (getChannel(gate, i)).onBuffer(checkpointBarrier.retainBuffer(), 0, 0);
        }

        assertEquals(0, target.getTriggeredCheckpointCounter());
        for (int i = 0; i < numChannels; i++) {
            assertAnnouncement(gate);
        }
        assertEquals(0, target.getTriggeredCheckpointCounter());

        clock.advanceTime(alignmentTimeout * 4, TimeUnit.MILLISECONDS);

        assertBarrier(gate);
        assertEquals(1, target.getTriggeredCheckpointCounter());
    }

    /**
     * First we process aligned {@link CheckpointBarrier} and after that we receive an already
     * unaligned {@link CheckpointBarrier}, that has timed out on an upstream task.
     */
    @Test
    public void testTimeoutAlignmentOnUnalignedCheckpoint() throws Exception {
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        RecordingChannelStateWriter channelStateWriter = new RecordingChannelStateWriter();
        CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                2, TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .withChannelStateWriter(channelStateWriter)
                        .withRemoteChannels()
                        .withMailboxExecutor()
                        .build();

        getChannel(gate, 0).onBuffer(withTimeout(Integer.MAX_VALUE).retainBuffer(), 0, 0);

        assertAnnouncement(gate);
        assertBarrier(gate);

        getChannel(gate, 1).onBuffer(dataBuffer(), 0, 0);
        getChannel(gate, 1).onBuffer(dataBuffer(), 1, 0);
        getChannel(gate, 1)
                .onBuffer(
                        toBuffer(
                                        new CheckpointBarrier(
                                                1,
                                                clock.relativeTimeMillis(),
                                                unaligned(getDefault())),
                                        true)
                                .retainBuffer(),
                        2,
                        0);

        assertBarrier(gate);

        assertEquals(
                channelStateWriter.getAddedInput().get(getChannel(gate, 1).getChannelInfo()).size(),
                2);
        assertEquals(1, target.getTriggeredCheckpointCounter());
    }

    private RemoteInputChannel getChannel(CheckpointedInputGate gate, int channelIndex) {
        return (RemoteInputChannel) gate.getChannel(channelIndex);
    }

    @Test
    public void testTimeoutAlignmentConsistencyOnPreProcessBarrier() throws Exception {
        testTimeoutAlignmentConsistency(true, false, false);
    }

    @Test
    public void testTimeoutAlignmentConsistencyOnProcessBarrier() throws Exception {
        testTimeoutAlignmentConsistency(false, true, false);
    }

    @Test
    public void testTimeoutAlignmentConsistencyOnPostProcessBarrier() throws Exception {
        testTimeoutAlignmentConsistency(false, false, true);
    }

    public void testTimeoutAlignmentConsistency(
            boolean sleepBeforePreProcess,
            boolean sleepBeforeProcess,
            boolean sleepBeforePostProcess)
            throws Exception {
        SingleInputGate gate = new SingleInputGateBuilder().setNumberOfChannels(1).build();
        TestInputChannel channel0 = new TestInputChannel(gate, 0, false, true);
        gate.setInputChannels(channel0);

        RecordingChannelStateWriter channelStateWriter = new RecordingChannelStateWriter();
        AlternatingController controller =
                new AlternatingController(
                        new AlignedController(gate),
                        new UnalignedController(
                                new TestSubtaskCheckpointCoordinator(channelStateWriter), gate),
                        clock);

        long alignmentTimeout = 10;
        CheckpointBarrier barrier =
                new CheckpointBarrier(
                        1, clock.relativeTimeMillis(), alignedNoTimeout(CHECKPOINT, getDefault()));

        InputChannelInfo channelInfo = channel0.getChannelInfo();

        controller.preProcessFirstBarrierOrAnnouncement(barrier);
        controller.barrierAnnouncement(channelInfo, barrier, 1);

        if (sleepBeforePreProcess) {
            clock.advanceTime(alignmentTimeout * 2, TimeUnit.MILLISECONDS);
        }
        Optional<CheckpointBarrier> preProcessTrigger =
                controller.preProcessFirstBarrier(channelInfo, barrier);
        if (sleepBeforeProcess) {
            clock.advanceTime(alignmentTimeout * 2, TimeUnit.MILLISECONDS);
        }
        Optional<CheckpointBarrier> processTrigger =
                controller.barrierReceived(channelInfo, barrier);
        if (sleepBeforePostProcess) {
            clock.advanceTime(alignmentTimeout * 2, TimeUnit.MILLISECONDS);
        }
        Optional<CheckpointBarrier> postProcessTrigger =
                controller.postProcessLastBarrier(channelInfo, barrier);

        int triggeredCount = 0;
        boolean unalignedCheckpoint = false;
        if (preProcessTrigger.isPresent()) {
            triggeredCount++;
            unalignedCheckpoint =
                    preProcessTrigger.get().getCheckpointOptions().isUnalignedCheckpoint();
            assertTrue(unalignedCheckpoint);
        }
        if (processTrigger.isPresent()) {
            triggeredCount++;
            unalignedCheckpoint =
                    processTrigger.get().getCheckpointOptions().isUnalignedCheckpoint();
            assertTrue(unalignedCheckpoint);
        }
        if (postProcessTrigger.isPresent()) {
            triggeredCount++;
            unalignedCheckpoint =
                    postProcessTrigger.get().getCheckpointOptions().isUnalignedCheckpoint();
        }

        assertEquals(
                String.format(
                        "Checkpoint should be triggered exactly once, but [%s, %s, %s] was found instead",
                        preProcessTrigger.isPresent(),
                        processTrigger.isPresent(),
                        postProcessTrigger.isPresent()),
                1,
                triggeredCount);

        if (unalignedCheckpoint) {
            // check that we can add output data if we are in unaligned checkpoint mode. In other
            // words
            // if the state writer has been initialised correctly.
            assertEquals(barrier.getId(), channelStateWriter.getLastStartedCheckpointId());
        }
    }

    @Test
    public void testMetricsAlternation() throws Exception {
        int numChannels = 2;
        int bufferSize = 1000;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                numChannels,
                                TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .build();

        long startNanos = clock.relativeTimeNanos();
        long checkpoint1CreationTime = clock.relativeTimeMillis() - 10;
        sendBarrier(1, checkpoint1CreationTime, CHECKPOINT, gate, 0);
        sendData(bufferSize, 0, gate);
        sendData(bufferSize, 1, gate);

        clock.advanceTime(6, TimeUnit.MILLISECONDS);
        sendBarrier(1, checkpoint1CreationTime, CHECKPOINT, gate, 1);
        sendData(bufferSize, 0, gate);

        assertMetrics(
                target,
                gate.getCheckpointBarrierHandler(),
                1L,
                startNanos,
                6_000_000L,
                10_000_000L,
                bufferSize * 2);

        startNanos = clock.relativeTimeNanos();
        long checkpoint2CreationTime = clock.relativeTimeMillis() - 5;
        sendBarrier(2, checkpoint2CreationTime, SAVEPOINT, gate, 0);
        sendData(bufferSize, 1, gate);

        assertMetrics(
                target,
                gate.getCheckpointBarrierHandler(),
                2L,
                startNanos,
                0L,
                5_000_000L,
                bufferSize * 2);
        clock.advanceTime(5, TimeUnit.MILLISECONDS);
        sendBarrier(2, checkpoint2CreationTime, SAVEPOINT, gate, 1);
        sendData(bufferSize, 0, gate);

        assertMetrics(
                target,
                gate.getCheckpointBarrierHandler(),
                2L,
                startNanos,
                5_000_000L,
                5_000_000L,
                bufferSize);

        startNanos = clock.relativeTimeNanos();
        long checkpoint3CreationTime = clock.relativeTimeMillis() - 7;
        send(barrier(3, checkpoint3CreationTime, unaligned(getDefault())), 0, gate);
        sendData(bufferSize, 0, gate);
        sendData(bufferSize, 1, gate);
        assertMetrics(
                target, gate.getCheckpointBarrierHandler(), 3L, startNanos, 0L, 7_000_000L, -1L);
        clock.advanceTime(10, TimeUnit.MILLISECONDS);
        send(barrier(3, checkpoint2CreationTime, unaligned(getDefault())), 1, gate);
        assertMetrics(
                target,
                gate.getCheckpointBarrierHandler(),
                3L,
                startNanos,
                10_000_000L,
                7_000_000L,
                bufferSize * 2);
    }

    @Test
    public void testMetricsSingleChannel() throws Exception {
        int numChannels = 1;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        CheckpointedInputGate gate =
                new TestCheckpointedInputGateBuilder(
                                numChannels,
                                TestBarrierHandlerFactory.forTarget(target).withClock(clock))
                        .build();

        long checkpoint1CreationTime = clock.relativeTimeMillis() - 10;
        long startNanos = clock.relativeTimeNanos();

        sendData(1000, 0, gate);
        sendBarrier(1, checkpoint1CreationTime, CHECKPOINT, gate, 0);
        sendData(1000, 0, gate);
        clock.advanceTime(6, TimeUnit.MILLISECONDS);
        assertMetrics(
                target, gate.getCheckpointBarrierHandler(), 1L, startNanos, 0L, 10_000_000L, 0);

        long checkpoint2CreationTime = clock.relativeTimeMillis() - 5;
        startNanos = clock.relativeTimeNanos();
        sendData(1000, 0, gate);
        sendBarrier(2, checkpoint2CreationTime, SAVEPOINT, gate, 0);
        sendData(1000, 0, gate);
        clock.advanceTime(5, TimeUnit.MILLISECONDS);
        assertMetrics(
                target, gate.getCheckpointBarrierHandler(), 2L, startNanos, 0L, 5_000_000L, 0);
    }

    private void assertMetrics(
            ValidatingCheckpointHandler target,
            CheckpointBarrierHandler checkpointBarrierHandler,
            long latestCheckpointId,
            long alignmentDurationStartNanos,
            long alignmentDurationNanosMin,
            long startDelayNanos,
            long bytesProcessedDuringAlignment) {
        assertThat(checkpointBarrierHandler.getLatestCheckpointId(), equalTo(latestCheckpointId));
        long alignmentDurationNanos = checkpointBarrierHandler.getAlignmentDurationNanos();
        long expectedAlignmentDurationNanosMax =
                clock.relativeTimeNanos() - alignmentDurationStartNanos;
        assertThat(alignmentDurationNanos, greaterThanOrEqualTo(alignmentDurationNanosMin));
        assertThat(alignmentDurationNanos, lessThanOrEqualTo(expectedAlignmentDurationNanosMax));
        assertThat(
                checkpointBarrierHandler.getCheckpointStartDelayNanos(),
                greaterThanOrEqualTo(startDelayNanos));
        assertThat(
                FutureUtils.getOrDefault(target.getLastBytesProcessedDuringAlignment(), -1L),
                equalTo(bytesProcessedDuringAlignment));
    }

    @Test
    public void testPreviousHandlerReset() throws Exception {
        SingleInputGate inputGate = new SingleInputGateBuilder().setNumberOfChannels(2).build();
        TestInputChannel[] channels = {
            new TestInputChannel(inputGate, 0), new TestInputChannel(inputGate, 1)
        };
        inputGate.setInputChannels(channels);
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        SingleCheckpointBarrierHandler barrierHandler =
                TestBarrierHandlerFactory.forTarget(target).withClock(clock).create(inputGate);

        for (int i = 0; i < 4; i++) {
            int channel = i % 2;
            CheckpointType type = channel == 0 ? SAVEPOINT : CHECKPOINT;
            target.setNextExpectedCheckpointId(-1);

            if (type.isSavepoint()) {
                channels[channel].setBlocked(true);
            }
            barrierHandler.processBarrier(
                    new CheckpointBarrier(
                            i,
                            clock.relativeTimeMillis(),
                            new CheckpointOptions(type, getDefault())),
                    new InputChannelInfo(0, channel));
            if (type.isSavepoint()) {
                assertTrue(channels[channel].isBlocked());
                assertFalse(channels[(channel + 1) % 2].isBlocked());
            } else {
                assertFalse(channels[0].isBlocked());
                assertFalse(channels[1].isBlocked());
            }

            assertTrue(barrierHandler.isCheckpointPending());
            assertFalse(barrierHandler.getAllBarriersReceivedFuture(i).isDone());

            channels[0].setBlocked(false);
            channels[1].setBlocked(false);
        }
    }

    @Test
    public void testHasInflightDataBeforeProcessBarrier() throws Exception {
        SingleInputGate inputGate = new SingleInputGateBuilder().setNumberOfChannels(2).build();
        inputGate.setInputChannels(
                new TestInputChannel(inputGate, 0), new TestInputChannel(inputGate, 1));
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        SingleCheckpointBarrierHandler barrierHandler =
                TestBarrierHandlerFactory.forTarget(target).withClock(clock).create(inputGate);

        final long id = 1;
        barrierHandler.processBarrier(
                new CheckpointBarrier(
                        id,
                        clock.relativeTimeMillis(),
                        new CheckpointOptions(CHECKPOINT, getDefault())),
                new InputChannelInfo(0, 0));

        assertFalse(barrierHandler.getAllBarriersReceivedFuture(id).isDone());
    }

    @Test
    public void testOutOfOrderBarrier() throws Exception {
        SingleInputGate inputGate = new SingleInputGateBuilder().setNumberOfChannels(2).build();
        TestInputChannel firstChannel = new TestInputChannel(inputGate, 0);
        TestInputChannel secondChannel = new TestInputChannel(inputGate, 1);
        inputGate.setInputChannels(firstChannel, secondChannel);
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        SingleCheckpointBarrierHandler barrierHandler =
                TestBarrierHandlerFactory.forTarget(target).withClock(clock).create(inputGate);

        long checkpointId = 10;
        long outOfOrderSavepointId = 5;

        barrierHandler.processBarrier(
                new CheckpointBarrier(
                        checkpointId,
                        clock.relativeTimeMillis(),
                        new CheckpointOptions(CHECKPOINT, getDefault())),
                new InputChannelInfo(0, 0));
        secondChannel.setBlocked(true);
        barrierHandler.processBarrier(
                new CheckpointBarrier(
                        outOfOrderSavepointId,
                        clock.relativeTimeMillis(),
                        new CheckpointOptions(SAVEPOINT, getDefault())),
                new InputChannelInfo(0, 1));

        assertEquals(checkpointId, barrierHandler.getLatestCheckpointId());
        assertFalse(secondChannel.isBlocked());
    }

    private void testBarrierHandling(CheckpointType checkpointType) throws Exception {
        final long barrierId = 123L;
        ValidatingCheckpointHandler target = new ValidatingCheckpointHandler();
        SingleInputGate gate = new SingleInputGateBuilder().setNumberOfChannels(2).build();
        TestInputChannel fast = new TestInputChannel(gate, 0, false, true);
        TestInputChannel slow = new TestInputChannel(gate, 1, false, true);
        gate.setInputChannels(fast, slow);
        SingleCheckpointBarrierHandler barrierHandler =
                TestBarrierHandlerFactory.forTarget(target).withClock(clock).create(gate);
        CheckpointedInputGate checkpointedGate =
                new CheckpointedInputGate(gate, barrierHandler, new SyncMailboxExecutor());

        if (checkpointType.isSavepoint()) {
            fast.setBlocked(true);
            slow.setBlocked(true);
        }

        CheckpointOptions options =
                checkpointType.isSavepoint()
                        ? alignedNoTimeout(checkpointType, getDefault())
                        : unaligned(getDefault());
        Buffer barrier = barrier(barrierId, 1, options);
        send(barrier.retainBuffer(), fast, checkpointedGate);
        assertEquals(checkpointType.isSavepoint(), target.triggeredCheckpoints.isEmpty());
        send(barrier.retainBuffer(), slow, checkpointedGate);

        assertEquals(singletonList(barrierId), target.triggeredCheckpoints);
        if (checkpointType.isSavepoint()) {
            for (InputChannel channel : gate.getInputChannels().values()) {
                assertFalse(
                        String.format("channel %d should be resumed", channel.getChannelIndex()),
                        ((TestInputChannel) channel).isBlocked());
            }
        }
    }

    private void sendBarrier(
            long barrierId,
            long barrierCreationTime,
            CheckpointType type,
            CheckpointedInputGate gate,
            int channelId)
            throws Exception {
        send(
                barrier(barrierId, barrierCreationTime, alignedNoTimeout(type, getDefault())),
                channelId,
                gate);
    }

    private void sendData(int dataSize, int channelId, CheckpointedInputGate gate)
            throws Exception {
        send(createBuffer(dataSize), channelId, gate);
    }

    private void send(Buffer buffer, int channelId, CheckpointedInputGate gate) throws Exception {
        send(buffer.retainBuffer(), gate.getChannel(channelId), gate);
    }

    private void send(Buffer buffer, InputChannel channel, CheckpointedInputGate checkpointedGate)
            throws IOException, InterruptedException {
        if (channel instanceof TestInputChannel) {
            ((TestInputChannel) channel).read(buffer);
        } else if (channel instanceof RemoteInputChannel) {
            ((RemoteInputChannel) channel).onBuffer(buffer, 0, 0);
        } else {
            throw new IllegalArgumentException("Unknown channel type: " + channel);
        }
        while (checkpointedGate.pollNext().isPresent()) {}
    }

    private Buffer withTimeout(long alignmentTimeout) throws IOException {
        return barrier(
                1, clock.relativeTimeMillis(), alignedWithTimeout(getDefault(), alignmentTimeout));
    }

    private Buffer barrier(long barrierId, long barrierTimestamp, CheckpointOptions options)
            throws IOException {
        CheckpointBarrier checkpointBarrier =
                new CheckpointBarrier(barrierId, barrierTimestamp, options);
        return toBuffer(
                checkpointBarrier,
                checkpointBarrier.getCheckpointOptions().isUnalignedCheckpoint());
    }

    private static void assertAnnouncement(CheckpointedInputGate gate)
            throws IOException, InterruptedException {
        assertEvent(gate, EventAnnouncement.class);
    }

    private static void assertBarrier(CheckpointedInputGate gate)
            throws IOException, InterruptedException {
        assertEvent(gate, CheckpointBarrier.class);
    }

    private static <T extends RuntimeEvent> void assertEvent(
            CheckpointedInputGate gate, Class<T> clazz) throws IOException, InterruptedException {
        Optional<BufferOrEvent> bufferOrEvent = assertPoll(gate);
        assertTrue(
                "expected event, got data buffer on " + bufferOrEvent.get().getChannelInfo(),
                bufferOrEvent.get().isEvent());
        assertEquals(clazz, bufferOrEvent.get().getEvent().getClass());
    }

    private static <T extends RuntimeEvent> void assertData(CheckpointedInputGate gate)
            throws IOException, InterruptedException {
        Optional<BufferOrEvent> bufferOrEvent = assertPoll(gate);
        assertTrue(
                "expected data, got "
                        + bufferOrEvent.get().getEvent()
                        + "  on "
                        + bufferOrEvent.get().getChannelInfo(),
                bufferOrEvent.get().isBuffer());
    }

    private static Optional<BufferOrEvent> assertPoll(CheckpointedInputGate gate)
            throws IOException, InterruptedException {
        Optional<BufferOrEvent> bufferOrEvent = gate.pollNext();
        assertTrue("empty gate", bufferOrEvent.isPresent());
        return bufferOrEvent;
    }
}
