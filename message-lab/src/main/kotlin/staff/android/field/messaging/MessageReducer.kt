package staff.android.field.messaging

import java.util.Collections

data class StreamScope(val accountId: String, val channelId: String)
data class StreamToken(val scope: StreamScope, val generation: Long, val epoch: String)
data class Message(val sequence: Long, val text: String)
data class StreamPolicy(val historyLimit: Int, val bufferLimit: Int, val maxAhead: Long)
enum class ResetReason { ConflictingSequence, GapTooLarge, BufferFull, EpochChanged, RetentionLost }
data class StreamState(
    val token: StreamToken,
    val cursor: Long,
    val history: List<Message>,
    val pending: List<Message>,
    val resetReason: ResetReason? = null,
)
sealed interface StreamEvent {
    data object Reconnect : StreamEvent
    data class Frame(val token: StreamToken, val message: Message) : StreamEvent
    data class Retention(val token: StreamToken, val earliest: Long) : StreamEvent
    data class Snapshot(
        val token: StreamToken, val cursor: Long, val history: List<Message>,
    ) : StreamEvent
}
enum class Outcome {
    Reconnected, Applied, Buffered, Duplicate, IgnoredOld, IgnoredScope,
    IgnoredGeneration, AwaitingSnapshot, ResetRequired, SnapshotInstalled,
    IgnoredSnapshotRewind, RetentionAvailable,
}
data class Reduction(val state: StreamState, val emitted: List<Message>, val outcome: Outcome)
fun interface MessageReducer {
    fun reduce(policy: StreamPolicy, state: StreamState, event: StreamEvent): Reduction
}

/** Deliberately unfinished learner entry point. */
object LearnerReducer : MessageReducer {
    override fun reduce(policy: StreamPolicy, state: StreamState, event: StreamEvent) =
        Reduction(state, emptyList(), Outcome.IgnoredOld)
}

/**
 * A bounded append-only log model, not transport, authentication or storage.
 * The adapter authenticates events and serializes one current snapshot request.
 * Input list structures must remain stable while their copies are constructed.
 */
object ReferenceReducer : MessageReducer {
    override fun reduce(
        policy: StreamPolicy, state: StreamState, event: StreamEvent,
    ): Reduction {
        val current = validatedState(policy, state)
        val incoming = validatedEvent(policy, event)
        if (incoming == StreamEvent.Reconnect) {
            val token = current.token.copy(
                generation = Math.addExact(current.token.generation, 1L),
            )
            return result(current.copy(token = token, pending = emptyList()), Outcome.Reconnected)
        }
        val token = when (incoming) {
            is StreamEvent.Frame -> incoming.token
            is StreamEvent.Retention -> incoming.token
            is StreamEvent.Snapshot -> incoming.token
            StreamEvent.Reconnect -> error("Handled above")
        }
        if (token.scope != current.token.scope) return result(current, Outcome.IgnoredScope)
        if (token.generation != current.token.generation) return result(current, Outcome.IgnoredGeneration)
        if (incoming is StreamEvent.Snapshot) {
            if (token.epoch == current.token.epoch && incoming.cursor < current.cursor) {
                return result(current, Outcome.IgnoredSnapshotRewind)
            }
            return result(
                StreamState(token, incoming.cursor, incoming.history, emptyList()),
                Outcome.SnapshotInstalled,
            )
        }
        if (current.resetReason != null) return result(current, Outcome.AwaitingSnapshot)
        if (token.epoch != current.token.epoch) return reset(current, ResetReason.EpochChanged)
        return when (incoming) {
            is StreamEvent.Frame -> frame(policy, current, incoming.message)
            is StreamEvent.Retention -> {
                if (incoming.earliest > current.cursor && incoming.earliest - current.cursor > 1) {
                    reset(current, ResetReason.RetentionLost)
                } else result(current, Outcome.RetentionAvailable)
            }
            else -> error("Handled above")
        }
    }

    private fun frame(policy: StreamPolicy, state: StreamState, message: Message): Reduction {
        if (message.sequence <= state.cursor) {
            val retained = state.history.find { it.sequence == message.sequence }
                ?: return result(state, Outcome.IgnoredOld)
            return if (retained == message) result(state, Outcome.Duplicate)
                else reset(state, ResetReason.ConflictingSequence)
        }
        val waiting = state.pending.find { it.sequence == message.sequence }
        if (waiting != null) {
            return if (waiting == message) result(state, Outcome.Duplicate)
                else reset(state, ResetReason.ConflictingSequence)
        }
        // This difference is safe: sequence > cursor >= 0.
        val distance = message.sequence - state.cursor
        if (distance > policy.maxAhead) return reset(state, ResetReason.GapTooLarge)
        if (distance > 1) {
            if (state.pending.size == policy.bufferLimit) return reset(state, ResetReason.BufferFull)
            return result(
                state.copy(pending = frozen((state.pending + message).sortedBy { it.sequence })),
                Outcome.Buffered,
            )
        }
        val pending = state.pending.associateBy { it.sequence }.toMutableMap()
        val emitted = mutableListOf(message)
        var cursor = message.sequence
        while (cursor < Long.MAX_VALUE) {
            val next = pending.remove(cursor + 1) ?: break
            emitted.add(next)
            cursor = next.sequence
        }
        return result(
            state.copy(
                cursor = cursor,
                history = frozen((state.history + emitted).takeLast(policy.historyLimit)),
                pending = frozen(pending.values.sortedBy { it.sequence }),
            ),
            Outcome.Applied, emitted,
        )
    }

    private fun validatedState(policy: StreamPolicy, state: StreamState): StreamState {
        require(policy.historyLimit in 1..32 && policy.bufferLimit in 0..16 && policy.maxAhead in 1..64)
        token(state.token)
        val history = history(state.cursor, state.history, policy)
        require(state.pending.size <= policy.bufferLimit)
        val pending = state.pending.toList()
        pending.forEach { message(it) }
        require(pending.map { it.sequence }.distinct().size == pending.size)
        pending.forEach {
            require(it.sequence > state.cursor && it.sequence - state.cursor > 1 &&
                it.sequence - state.cursor <= policy.maxAhead)
        }
        require(state.resetReason == null || pending.isEmpty())
        return state.copy(history = history, pending = frozen(pending.sortedBy { it.sequence }))
    }

    private fun validatedEvent(policy: StreamPolicy, event: StreamEvent): StreamEvent = when (event) {
        StreamEvent.Reconnect -> event
        is StreamEvent.Frame -> { token(event.token); message(event.message); event }
        is StreamEvent.Retention -> { token(event.token); require(event.earliest > 0); event }
        is StreamEvent.Snapshot -> {
            token(event.token)
            event.copy(history = history(event.cursor, event.history, policy))
        }
    }

    private fun history(cursor: Long, values: List<Message>, policy: StreamPolicy): List<Message> {
        require(cursor >= 0 && values.size <= policy.historyLimit)
        val copy = values.toList()
        copy.forEach { message(it) }
        require((cursor == 0L) == copy.isEmpty())
        if (copy.isNotEmpty()) {
            require(copy.last().sequence == cursor)
            copy.zipWithNext().forEach { (a, b) ->
                require(b.sequence > a.sequence && b.sequence - a.sequence == 1L)
            }
        }
        return frozen(copy)
    }

    private fun token(value: StreamToken) {
        val id = Regex("[a-z][a-z0-9_-]{0,31}")
        require(value.scope.accountId.matches(id) && value.scope.channelId.matches(id))
        require(value.epoch.matches(id) && value.generation >= 0)
    }

    private fun message(value: Message) {
        require(value.sequence > 0 && value.text.length in 1..256 && value.text.isNotBlank())
    }

    private fun reset(state: StreamState, reason: ResetReason): Reduction =
        result(state.copy(pending = emptyList(), resetReason = reason), Outcome.ResetRequired)

    private fun result(
        state: StreamState, outcome: Outcome, emitted: List<Message> = emptyList(),
    ): Reduction = Reduction(state, frozen(emitted), outcome)

    private fun <T> frozen(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
