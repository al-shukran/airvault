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
