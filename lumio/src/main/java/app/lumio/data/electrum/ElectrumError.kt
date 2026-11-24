package app.lumio.data.electrum

/**
 * Why an Electrum call failed. [Transport] is a failure of one server's connection: it cannot be reached, it refuses the certificate, it stops
 * answering, or it sends what is not Electrum. The pool moves on to the next server. [Rpc] is a server's answer to one request, such as "no such
 * transaction". It is an answer, so no other server is asked. [Unavailable] means that every server failed.
 */
sealed class ElectrumError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Transport(message: String, cause: Throwable? = null) : ElectrumError(message, cause)

    class Rpc(val code: Int?, message: String) : ElectrumError(message)

    class Unavailable(val failures: List<Transport>) : ElectrumError("no Electrum server answered")
}
