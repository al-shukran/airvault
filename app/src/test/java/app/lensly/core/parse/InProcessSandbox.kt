package app.lensly.core.parse

/**
 * The Parser Sandbox without a process. Each call is answered in this process by the same [answer] the isolated service runs,
 * so tests cross the same wire format the app does.
 */
internal object InProcessSandbox : SandboxTransport {
    override fun call(method: Int, payload: ByteArray): ByteArray = answer(InProcessParser, method, payload)

    override fun reset() = Unit
}

/** The app's parser over [InProcessSandbox]. */
internal val inProcessSandboxParser: RequestParser = SandboxedParser(InProcessSandbox)

/**
 * A sandbox whose answer a test scripts: [reply] returns the reply to each call, or throws, as a crashed or silent sandbox
 * would. It counts the calls it got and the resets the app made.
 */
internal class ScriptedSandbox(private val reply: (Int, ByteArray) -> ByteArray) : SandboxTransport {
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
