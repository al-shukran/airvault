package app.lensly.tools.registrypack

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.system.exitProcess
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

private const val USAGE = """usage:
  keygen --out <dir>
  build  --in <entries.json> --key <release.key> --out <file.lrp>
  verify --pub <release.pub> <file.lrp>
Paths are relative to the repository root when run as: ./gradlew :tools:registry-pack:run --args="..."
Exit codes: 0 success, 1 refused (invalid input or signature), 2 usage error."""

fun main(args: Array<String>) {
    exitProcess(runTool(args.toList(), System.out, System.err))
}

internal fun runTool(args: List<String>, out: PrintStream, err: PrintStream): Int = try {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "keygen" -> keygen(parseOptions(rest, setOf("--out")), out)
        "build" -> build(parseOptions(rest, setOf("--in", "--key", "--out")), out)
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

private fun keygen(options: Options, out: PrintStream) {
    options.requireNoPositional()
    val dir = Paths.get(options.required("--out"))
    val publicKey = generateKeys(dir)
    out.println("wrote ${dir.resolve("release.key")} (private: keep it out of the repository)")
    out.println("wrote ${dir.resolve("release.pub")}")
    out.println("public key ${publicKey.toHex()}")
}

private fun build(options: Options, out: PrintStream) {
    options.requireNoPositional()
    val entries = Files.readString(Paths.get(options.required("--in")))
    val seed = readKey(Paths.get(options.required("--key")))
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
        "${content.networks.size} network(s), ${content.tokens.size} token(s), ${content.whitelist.size} whitelist entry(ies)"

/** Writes a new private seed (`release.key`, mode 600) and its public key (`release.pub`). Never overwrites. */
internal fun generateKeys(dir: Path): ByteArray {
    val privateFile = dir.resolve("release.key")
    val publicFile = dir.resolve("release.pub")
    require(!Files.exists(privateFile) && !Files.exists(publicFile)) { "refusing to overwrite the keys in $dir" }
    Files.createDirectories(dir)
    val seed = Ed25519PrivateKeyParameters(SecureRandom()).encoded
    val publicKey = Pack.publicKeyOf(seed)
    Files.createFile(privateFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(privateFile, seed.toHex() + "\n")
    Files.writeString(publicFile, publicKey.toHex() + "\n", StandardOpenOption.CREATE_NEW)
    return publicKey
}

/** Reads a 32-byte key stored as 64 hex characters. */
internal fun readKey(path: Path): ByteArray {
    val bytes = Files.readString(path).hexToBytes()
    require(bytes.size == 32) { "$path must hold a 32-byte key as 64 hex characters" }
    return bytes
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
