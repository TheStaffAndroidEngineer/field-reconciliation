package staff.android.field.messaging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageReducerTest {
    private val reducer: MessageReducer = when (System.getProperty("message.solution", "reference")) {
        "learner" -> LearnerReducer
        "reference" -> ReferenceReducer
        else -> error("message.solution must be learner or reference")
    }
    private val policy = StreamPolicy(3, 2, 8)
    private val token = StreamToken(StreamScope("maya", "dispatch"), 4, "spring")
    private fun msg(sequence: Long, text: String = "message-$sequence") = Message(sequence, text)
    private fun base() = StreamState(token, 10, listOf(msg(8), msg(9), msg(10)), emptyList())
    private fun apply(event: StreamEvent, state: StreamState = base(), p: StreamPolicy = policy) =
        reducer.reduce(p, state, event)
    private fun frame(sequence: Long, text: String = "message-$sequence", t: StreamToken = token) =
        StreamEvent.Frame(t, msg(sequence, text))

    @Test fun nextMessageAdvancesCursorAndEmits() {
        val r = apply(frame(11))
        assertEquals(11, r.state.cursor); assertEquals(listOf(msg(11)), r.emitted)
        assertEquals(Outcome.Applied, r.outcome)
        assertEquals(listOf(msg(9), msg(10), msg(11)), r.state.history)
    }
    @Test fun futureMessageDoesNotAdvanceCursor() {
        val r = apply(frame(12))
        assertEquals(10, r.state.cursor); assertEquals(listOf(msg(12)), r.state.pending)
        assertTrue(r.emitted.isEmpty()); assertEquals(Outcome.Buffered, r.outcome)
    }
    @Test fun gapFillDrainsOnlyContiguousPrefix() {
        val state = base().copy(pending = listOf(msg(14), msg(12)))
        val r = apply(frame(11), state)
        assertEquals(12, r.state.cursor); assertEquals(listOf(msg(11), msg(12)), r.emitted)
        assertEquals(listOf(msg(14)), r.state.pending)
    }
    @Test fun committedDuplicateDoesNotEmitAgain() {
        val r = apply(frame(9))
        assertEquals(Outcome.Duplicate, r.outcome); assertTrue(r.emitted.isEmpty())
        assertEquals(base(), r.state)
    }
    @Test fun pendingDuplicateDoesNotConsumeCapacity() {
        val state = base().copy(pending = listOf(msg(12), msg(13)))
        val r = apply(frame(12), state)
        assertEquals(Outcome.Duplicate, r.outcome); assertEquals(state, r.state)
    }
    @Test fun retainedConflictRequiresSnapshot() {
        val r = apply(frame(9, "different"))
        assertEquals(ResetReason.ConflictingSequence, r.state.resetReason)
        assertEquals(10, r.state.cursor); assertEquals(base().history, r.state.history)
    }
    @Test fun pendingConflictClearsUncommittedBuffer() {
        val r = apply(frame(12, "different"), base().copy(pending = listOf(msg(12))))
        assertEquals(ResetReason.ConflictingSequence, r.state.resetReason)
        assertTrue(r.state.pending.isEmpty()); assertTrue(r.emitted.isEmpty())
    }
    @Test fun oldMessageOutsideHistoryIsExplicitlyUnverifiable() {
        val r = apply(frame(2, "not-compared"))
        assertEquals(Outcome.IgnoredOld, r.outcome); assertNull(r.state.resetReason)
    }
    @Test fun exceedingGapWindowRequiresSnapshot() {
        val r = apply(frame(19))
        assertEquals(ResetReason.GapTooLarge, r.state.resetReason)
        assertEquals(Outcome.ResetRequired, r.outcome)
    }
    @Test fun inclusiveGapBoundaryCanBuffer() {
        val r = apply(frame(18))
        assertEquals(Outcome.Buffered, r.outcome); assertEquals(listOf(msg(18)), r.state.pending)
    }
    @Test fun fullBufferDoesNotEvictToPretendContinuity() {
        val r = apply(frame(14), base().copy(pending = listOf(msg(12), msg(13))))
        assertEquals(ResetReason.BufferFull, r.state.resetReason)
        assertEquals(10, r.state.cursor); assertTrue(r.state.pending.isEmpty())
    }
    @Test fun nextMessageCanDrainAFullBuffer() {
        val r = apply(frame(11), base().copy(pending = listOf(msg(12), msg(13))))
        assertEquals(13, r.state.cursor); assertEquals(listOf(msg(11), msg(12), msg(13)), r.emitted)
        assertNull(r.state.resetReason)
    }
    @Test fun zeroBufferStillAcceptsTheNextMessage() {
        val p = policy.copy(bufferLimit = 0)
        assertEquals(Outcome.Applied, apply(frame(11), p = p).outcome)
        assertEquals(ResetReason.BufferFull, apply(frame(12), p = p).state.resetReason)
    }
    @Test fun resetIsStickyUntilSnapshot() {
        val state = base().copy(resetReason = ResetReason.BufferFull)
        val r = apply(frame(11), state)
        assertEquals(Outcome.AwaitingSnapshot, r.outcome); assertEquals(state, r.state)
    }
    @Test fun differentEpochRequiresSnapshot() {
        val r = apply(frame(11, t = token.copy(epoch = "summer")))
        assertEquals(ResetReason.EpochChanged, r.state.resetReason)
        assertEquals("spring", r.state.token.epoch)
    }
    @Test fun reconnectAdvancesLocalGenerationAndClearsPendingOnly() {
        val state = base().copy(pending = listOf(msg(12)))
        val r = apply(StreamEvent.Reconnect, state)
        assertEquals(5, r.state.token.generation); assertEquals("spring", r.state.token.epoch)
        assertEquals(10, r.state.cursor); assertEquals(state.history, r.state.history)
        assertTrue(r.state.pending.isEmpty()); assertEquals(Outcome.Reconnected, r.outcome)
    }
    @Test fun reconnectPreservesNeedForSnapshot() {
        val r = apply(StreamEvent.Reconnect, base().copy(resetReason = ResetReason.RetentionLost))
        assertEquals(ResetReason.RetentionLost, r.state.resetReason)
        assertEquals(5, r.state.token.generation)
    }
    @Test fun oldConnectionCallbackCannotPublish() {
        val r = apply(frame(11, t = token.copy(generation = 3)))
        assertEquals(Outcome.IgnoredGeneration, r.outcome); assertEquals(base(), r.state)
    }
    @Test fun futureGenerationDoesNotImplicitlyConnect() {
        assertEquals(Outcome.IgnoredGeneration, apply(frame(11, t = token.copy(generation = 5))).outcome)
    }
    @Test fun anotherAccountCannotAdvanceThisStream() {
        val foreign = token.copy(scope = token.scope.copy(accountId = "leo"))
        assertEquals(Outcome.IgnoredScope, apply(frame(11, t = foreign)).outcome)
    }
    @Test fun anotherChannelCannotAdvanceThisStream() {
        val foreign = token.copy(scope = token.scope.copy(channelId = "social"))
        assertEquals(Outcome.IgnoredScope, apply(frame(11, t = foreign)).outcome)
    }
    @Test fun scopeFilteringPrecedesGenerationFiltering() {
        val foreign = token.copy(scope = StreamScope("leo", "social"), generation = 0)
        assertEquals(Outcome.IgnoredScope, apply(frame(11, t = foreign)).outcome)
    }
    @Test fun retentionThroughNextMessagePermitsResume() {
        assertEquals(Outcome.RetentionAvailable, apply(StreamEvent.Retention(token, 11)).outcome)
        assertEquals(Outcome.RetentionAvailable, apply(StreamEvent.Retention(token, 3)).outcome)
    }
    @Test fun missingNextMessageInRetainedLogRequiresSnapshot() {
        val r = apply(StreamEvent.Retention(token, 12))
        assertEquals(ResetReason.RetentionLost, r.state.resetReason)
        assertEquals(base().history, r.state.history)
    }
    @Test fun snapshotReplacesProjectionAndClearsStickyReset() {
        val rows = listOf(msg(18), msg(19), msg(20))
        val r = apply(StreamEvent.Snapshot(token, 20, rows), base().copy(resetReason = ResetReason.RetentionLost))
        assertEquals(Outcome.SnapshotInstalled, r.outcome); assertEquals(20, r.state.cursor)
        assertEquals(rows, r.state.history); assertNull(r.state.resetReason)
        assertTrue(r.state.pending.isEmpty()); assertTrue(r.emitted.isEmpty())
    }
    @Test fun sameEpochSnapshotCannotRewindCursor() {
        val r = apply(StreamEvent.Snapshot(token, 9, listOf(msg(9))))
        assertEquals(Outcome.IgnoredSnapshotRewind, r.outcome); assertEquals(base(), r.state)
    }
    @Test fun newEpochCanInstallLowerAuthoritativeCursor() {
        val newToken = token.copy(epoch = "summer")
        val r = apply(StreamEvent.Snapshot(newToken, 1, listOf(msg(1))))
        assertEquals(1, r.state.cursor); assertEquals(newToken, r.state.token)
        assertEquals(Outcome.SnapshotInstalled, r.outcome)
    }
    @Test fun newEpochCanInstallEmptyLog() {
        val r = apply(StreamEvent.Snapshot(token.copy(epoch = "summer"), 0, emptyList()))
        assertEquals(0, r.state.cursor); assertTrue(r.state.history.isEmpty())
    }
    @Test fun oldGenerationSnapshotCannotReplaceCurrentEpoch() {
        val r = apply(StreamEvent.Snapshot(token.copy(generation = 3, epoch = "old"), 1, listOf(msg(1))))
        assertEquals(Outcome.IgnoredGeneration, r.outcome); assertEquals(base(), r.state)
    }
    @Test fun equalCursorSnapshotMayReplaceAuthoritativeProjection() {
        val r = apply(StreamEvent.Snapshot(token, 10, listOf(msg(10, "snapshot authority"))))
        assertEquals(Outcome.SnapshotInstalled, r.outcome)
        assertEquals("snapshot authority", r.state.history.single().text)
    }
    @Test fun maximumCursorDoesNotOverflowSuccessorArithmetic() {
        val state = StreamState(token, Long.MAX_VALUE, listOf(msg(Long.MAX_VALUE)), emptyList())
        assertEquals(Outcome.Duplicate, apply(frame(Long.MAX_VALUE), state).outcome)
        assertEquals(Outcome.RetentionAvailable, apply(StreamEvent.Retention(token, Long.MAX_VALUE), state).outcome)
    }
    @Test fun committingMaximumSequenceDoesNotOverflowDrain() {
        val state = StreamState(token, Long.MAX_VALUE - 1, listOf(msg(Long.MAX_VALUE - 1)), emptyList())
        val r = apply(frame(Long.MAX_VALUE), state)
        assertEquals(Long.MAX_VALUE, r.state.cursor); assertEquals(Outcome.Applied, r.outcome)
    }
    @Test fun generationOverflowIsExplicit() {
        assertThrows(ArithmeticException::class.java) {
            apply(StreamEvent.Reconnect, base().copy(token = token.copy(generation = Long.MAX_VALUE)))
        }
    }
    @Test fun invalidPolicyRejectedBeforeWrongScopeCanHideIt() {
        val bad = listOf(policy.copy(historyLimit = 0), policy.copy(historyLimit = 33),
            policy.copy(bufferLimit = -1), policy.copy(bufferLimit = 17), policy.copy(maxAhead = 0), policy.copy(maxAhead = 65))
        bad.forEach { p -> assertThrows(IllegalArgumentException::class.java) { apply(frame(11, t = token.copy(generation = 0)), p = p) } }
    }
    @Test fun malformedEventRejectedEvenForOldConnection() {
        assertThrows(IllegalArgumentException::class.java) { apply(frame(0, t = token.copy(generation = 0))) }
        assertThrows(IllegalArgumentException::class.java) { apply(frame(11, " ", token.copy(generation = 0))) }
        assertThrows(IllegalArgumentException::class.java) { apply(StreamEvent.Retention(token, 0)) }
    }
    @Test fun invalidIdentityAndGenerationAreRejected() {
        val invalid = listOf(token.copy(epoch = ""), token.copy(generation = -1),
            token.copy(scope = StreamScope("Upper", "dispatch")), token.copy(scope = StreamScope("maya", "")))
        invalid.forEach { t -> assertThrows(IllegalArgumentException::class.java) { apply(frame(11, t = t)) } }
    }
    @Test fun malformedHistoryAndSnapshotAreRejected() {
        val histories = listOf(emptyList(), listOf(msg(8), msg(10)), listOf(msg(10), msg(9)), listOf(msg(9)))
        histories.forEach { h -> assertThrows(IllegalArgumentException::class.java) { apply(frame(11), base().copy(history = h)) } }
        assertThrows(IllegalArgumentException::class.java) { apply(StreamEvent.Snapshot(token, 0, listOf(msg(1)))) }
        assertThrows(IllegalArgumentException::class.java) { apply(StreamEvent.Snapshot(token, 10, emptyList())) }
    }
    @Test fun malformedPendingAndResetStateAreRejected() {
        val pending = listOf(listOf(msg(11)), listOf(msg(12), msg(12)), listOf(msg(19)), listOf(msg(12), msg(13), msg(14)))
        pending.forEach { p -> assertThrows(IllegalArgumentException::class.java) { apply(frame(11), base().copy(pending = p)) } }
        assertThrows(IllegalArgumentException::class.java) {
            apply(frame(11), base().copy(pending = listOf(msg(12)), resetReason = ResetReason.BufferFull))
        }
    }
    @Test fun inputListsCannotMutateReturnedState() {
        val history = base().history.toMutableList(); val pending = mutableListOf(msg(12))
        val r = apply(frame(13), base().copy(history = history, pending = pending))
        history.clear(); pending.clear()
        assertEquals(3, r.state.history.size); assertEquals(listOf(msg(12), msg(13)), r.state.pending)
        assertThrows(UnsupportedOperationException::class.java) { (r.state.history as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (r.state.pending as MutableList).clear() }
    }
    @Test fun snapshotAndEmissionListsAreOwnedByTheResult() {
        val source = mutableListOf(msg(20)); val snapshot = apply(StreamEvent.Snapshot(token, 20, source))
        source.clear(); assertEquals(listOf(msg(20)), snapshot.state.history)
        val emitted = apply(frame(11)).emitted
        assertThrows(UnsupportedOperationException::class.java) { (emitted as MutableList).clear() }
    }
    @Test fun allFourMessageOrdersCommitTheSameContiguousHistory() {
        fun permutations(xs: List<Long>): List<List<Long>> = if (xs.isEmpty()) listOf(emptyList())
            else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }
        for (order in permutations(listOf(1, 2, 3, 4))) {
            var state = StreamState(token, 0, emptyList(), emptyList())
            val emitted = mutableListOf<Message>()
            for (sequence in order) {
                val r = apply(frame(sequence), state, StreamPolicy(4, 4, 4))
                state = r.state; emitted.addAll(r.emitted)
                assertNull(state.resetReason)
            }
            assertEquals(4, state.cursor, "order=$order")
            assertEquals((1L..4).map { msg(it) }, state.history)
            assertEquals((1L..4).map { msg(it) }, emitted)
        }
    }
}
