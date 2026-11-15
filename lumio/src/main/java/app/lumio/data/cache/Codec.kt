package app.lumio.data.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * A codec that stores a value as JSON. The cached types are serializable classes, so a stored value needs no format of its own, and a value that does
 * not decode is a miss (see the scheduler).
 */
class JsonCodec<T>(private val serializer: KSerializer<T>) : Codec<T> {
    override fun encode(value: T): ByteArray = Json.encodeToString(serializer, value).encodeToByteArray()

    override fun decode(bytes: ByteArray): T = Json.decodeFromString(serializer, bytes.decodeToString())
}

/** The JSON codec of the serializable type [T]. */
inline fun <reified T> jsonCodec(): Codec<T> = JsonCodec(serializer<T>())
