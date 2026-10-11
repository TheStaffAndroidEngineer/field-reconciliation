package staff.android.field.messaging

fun main() {
    val policy = StreamPolicy(historyLimit = 3, bufferLimit = 2, maxAhead = 8)
    val token = StreamToken(StreamScope("maya", "dispatch"), generation = 4, epoch = "spring")
    var state = StreamState(token, 10, listOf(Message(10, "At the depot")), emptyList())
    for (message in listOf(Message(12, "Bring the label"), Message(11, "Use the north entrance"))) {
        val result = ReferenceReducer.reduce(policy, state, StreamEvent.Frame(token, message))
        state = result.state
        println("${result.outcome}: cursor=${state.cursor}, pending=${state.pending.map { it.sequence }}, emitted=${result.emitted.map { it.sequence }}")
    }
    val reset = ReferenceReducer.reduce(policy, state, StreamEvent.Retention(token, earliest = 20))
    println("${reset.outcome}: ${reset.state.resetReason}; committed view retained=${reset.state.history.map { it.sequence }}")
}
