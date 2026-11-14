package app.lensly.tools.registrypack

import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/** The environment variable that holds the release key's passphrase. Without it, the tool asks at the terminal. */
const val PASSPHRASE_ENV = "LENSLY_REGISTRY_PASSPHRASE"

/** Supplies the passphrase. [confirm] is true when a new key is made, so the passphrase is typed twice. */
internal typealias PassphraseSource = (confirm: Boolean) -> CharArray

private const val MIN_PASSPHRASE_CHARS = 12

private val USAGE = """usage:
  keygen --out <dir>
  build  --in <entries.json> --key <release.key> --out <file.lrp>
  verify --pub <release.pub> <file.lrp>
Paths are relative to the repository root when run as: ./gradlew :tools:registry-pack:run --args="..."
The passphrase comes from $PASSPHRASE_ENV, or is typed at the terminal (keygen asks twice).
Exit codes: 0 success, 1 refused (invalid input, wrong passphrase or signature), 2 usage error."""

fun main(args: Array<String>) {
    exitProcess(runTool(args.toList(), System.out, System.err, ::promptPassphrase))
}

internal fun runTool(args: List<String>, out: PrintStream, err: PrintStream, passphrase: PassphraseSource): Int = try {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "keygen" -> keygen(parseOptions(rest, setOf("--out")), out, passphrase)
        "build" -> build(parseOptions(rest, setOf("--in", "--key", "--out")), out, passphrase)
        "verify" -> verify(parseOptions(rest, setOf("--pub")), out)
        else -> throw UsageError("")
    }
    0
} catch (e: UsageError) {
    if (!e.message.isNullOrEmpty()) err.println("error: ${e.message}")
    err.println(USAGE)
    2
} catch (e: IllegalArgumentException) {
    // Validation failures, including malformed JSON (kotlinx SerializationException is an IllegalArgumentException).
    err.println("refused: ${e.message}")
    1
} catch (e: IOException) {
    err.println("error: ${e.message}")
    1
}

private fun keygen(options: Options, out: PrintStream, passphrase: PassphraseSource) {
    options.requireNoPositional()
    val dir = Paths.get(options.required("--out"))
    val publicKey = generateKeys(dir, passphrase(true))
    val keyId = Pack.keyIdOf(publicKey).toHex()
    out.println("wrote ${dir.resolve("release.key")} (encrypted; keep it out of the repository)")
    out.println("wrote ${dir.resolve("release.pub")}")
    out.println("public key ${publicKey.toHex()}, key ID $keyId")
    out.println(
        """entry for app/src/main/assets/registry/trusted-keys.json: """ +
            """{"id":"$keyId","publicKey":"${publicKey.toHex()}","status":"active"}""",
    )
}

private fun build(options: Options, out: PrintStream, passphrase: PassphraseSource) {
    options.requireNoPositional()
    val entries = Files.readString(Paths.get(options.required("--in")))
    val seed = decryptSeed(Files.readString(Paths.get(options.required("--key"))), passphrase(false))
    val target = Paths.get(options.required("--out"))
    val (bytes, content) = Pack.build(entries, seed)
    Files.write(target, bytes)
    out.println("wrote $target: ${bytes.size} bytes, sha256 ${sha256(bytes)}")
    out.println(summary(content))
}

private fun verify(options: Options, out: PrintStream) {
    val publicKey = readKey(Paths.get(options.required("--pub")))
    val file = options.positional.singleOrNull() ?: throw UsageError("verify takes exactly one package file")
    val content = Pack.verify(Files.readAllBytes(Paths.get(file)), publicKey)
    out.println("ok: ${summary(content)}")
}

private fun summary(content: PackageDto): String =
    "sequence ${content.sequence}, created ${content.createdAt}, " +
        "${content.networks.size} network(s), ${content.tokens.size} token(s), ${content.whitelist.size} whitelist entry(ies), " +
        "${content.denylist?.let { "${it.size} denylist entry(ies)" } ?: "no denylist"}, " +
        "${content.wallets?.let { "${it.size} wallet(s)" } ?: "no wallets"}"

/**
 * Writes a new key: the private seed sealed under [passphrase] (`release.key`, mode 600) and its public key (`release.pub`).
 * Never overwrites.
 */
internal fun generateKeys(dir: Path, passphrase: CharArray): ByteArray {
    val privateFile = dir.resolve("release.key")
    val publicFile = dir.resolve("release.pub")
    require(!Files.exists(privateFile) && !Files.exists(publicFile)) { "refusing to overwrite the keys in $dir" }
    require(passphrase.size >= MIN_PASSPHRASE_CHARS) { "the passphrase must be at least $MIN_PASSPHRASE_CHARS characters" }
    Files.createDirectories(dir)
    val seed = Ed25519PrivateKeyParameters(SecureRandom()).encoded
    val publicKey = Pack.publicKeyOf(seed)
    Files.createFile(privateFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(privateFile, encryptSeed(seed, passphrase) + "\n")
    Files.writeString(publicFile, publicKey.toHex() + "\n", StandardOpenOption.CREATE_NEW)
    return publicKey
}

/** Reads a 32-byte key stored as 64 hex characters. */
internal fun readKey(path: Path): ByteArray {
    val bytes = Files.readString(path).hexToBytes()
    require(bytes.size == 32) { "$path must hold a 32-byte key as 64 hex characters" }
    return bytes
}

private val KEY_MAGIC = "LRK1".toByteArray(StandardCharsets.US_ASCII)
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12

/**
 * The seed sealed under [passphrase]: `LRK1` | salt | nonce | AES-256-GCM ciphertext and tag, as hexadecimal. The key is
 * derived with scrypt, which is memory-hard, so guessing the passphrase is slow.
 */
internal fun encryptSeed(seed: ByteArray, passphrase: CharArray): String {
    val random = SecureRandom()
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(128, nonce))
    cipher.updateAAD(KEY_MAGIC)
    return (KEY_MAGIC + salt + nonce + cipher.doFinal(seed)).toHex()
}

/** The seed in [text], which [encryptSeed] wrote. A wrong passphrase or an altered file is refused. */
internal fun decryptSeed(text: String, passphrase: CharArray): ByteArray {
    val blob = text.trim().hexToBytes()
    val headerBytes = KEY_MAGIC.size + SALT_BYTES + NONCE_BYTES
    require(blob.size > headerBytes && blob.copyOf(KEY_MAGIC.size).contentEquals(KEY_MAGIC)) {
        "not an encrypted registry key"
    }
    val salt = blob.copyOfRange(KEY_MAGIC.size, KEY_MAGIC.size + SALT_BYTES)
    val nonce = blob.copyOfRange(KEY_MAGIC.size + SALT_BYTES, headerBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(128, nonce))
    cipher.updateAAD(KEY_MAGIC)
    return try {
        cipher.doFinal(blob.copyOfRange(headerBytes, blob.size))
    } catch (e: AEADBadTagException) {
        throw IllegalArgumentException("wrong passphrase, or the key file was altered")
    }
}

/** A 256-bit AES key from [passphrase]: scrypt with N = 2^17, r = 8, p = 1, the parameters OWASP recommends. */
private fun keyFrom(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
    val password = String(passphrase).toByteArray(StandardCharsets.UTF_8)
    return SecretKeySpec(SCrypt.generate(password, salt, 1 shl 17, 8, 1, 32), "AES")
}

/** The passphrase from [PASSPHRASE_ENV], or typed at the terminal. A new key's passphrase is typed twice. */
internal fun promptPassphrase(confirm: Boolean): CharArray {
    System.getenv(PASSPHRASE_ENV)?.let { return it.toCharArray() }
    val console = System.console()
        ?: throw IllegalArgumentException("set $PASSPHRASE_ENV, or run in a terminal to type the passphrase")
    val first = console.readPassword("Release key passphrase: ") ?: throw IllegalArgumentException("no passphrase was typed")
    if (confirm) {
        val again = console.readPassword("Type it again: ")
        require(again != null && again.contentEquals(first)) { "the two passphrases do not match" }
    }
    return first
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private class UsageError(message: String) : IllegalStateException(message)

private class Options(private val values: Map<String, String>, val positional: List<String>) {
    fun required(name: String): String = values[name] ?: throw UsageError("missing $name")

    fun requireNoPositional() {
        positional.firstOrNull()?.let { throw UsageError("unexpected argument $it") }
    }
}

/** Parses `--name value` pairs for the names in [names]; everything else is positional. */
private fun parseOptions(args: List<String>, names: Set<String>): Options {
    val values = HashMap<String, String>()
    val positional = ArrayList<String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (arg.startsWith("--")) {
            if (arg !in names) throw UsageError("unknown option $arg")
            values[arg] = args.getOrNull(index + 1) ?: throw UsageError("$arg needs a value")
            index += 2
        } else {
            positional += arg
            index++
        }
    }
    return Options(values, positional)
}
