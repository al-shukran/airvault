package app.lensly.core.parse

/**
 * The Parser Sandbox without a process. Each call is answered in this process by the same [SandboxAnswers] the isolated service runs,
 * so tests cross the same wire format the app does.
 */
object InProcessSandbox : SandboxTransport {
    private val answers = SandboxAnswers()

    override fun call(method: Int, payload: ByteArray): ByteArray = answers.answer(method, payload)

    override fun reset() = Unit
}

/**
 * The app's parser over [InProcessSandbox]. Its reply wait is far longer than any test's scan, so a slow CI runner cannot turn a
 * scan into a refusal. A test that wants a short wait builds its own [SandboxedParser].
 */
val inProcessSandboxParser: RequestParser = SandboxedParser(InProcessSandbox, timeoutMillis = 120_000L)

/**
 * A sandbox whose answer a test scripts: [reply] returns the reply to each call, or throws, as a crashed or silent sandbox
 * would. It counts the calls it got and the resets the app made.
 */
class ScriptedSandbox(private val reply: (Int, ByteArray) -> ByteArray) : SandboxTransport {
    var calls = 0
    var resets = 0

    override fun call(method: Int, payload: ByteArray): ByteArray {
        calls++
        return reply(method, payload)
    }

    override fun reset() {
        resets++
    }
}
