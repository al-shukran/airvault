package app.lensly.core.registry.update

/** Why a registry package was refused. Each reason has its own class, so a caller can show a specific message. */
sealed class RegistryUpdateException(message: String) : Exception(message) {
    /** Not a registry package: wrong magic or lengths, over the size cap, truncated, or unreadable. */
    class Malformed(message: String) : RegistryUpdateException(message)

    /** No trusted key verifies the signature over the payload. */
    class BadSignature : RegistryUpdateException("the signature does not verify with a trusted key")

    /** The payload declares a format version this app does not read. */
    class UnsupportedFormat(val version: Long) :
        RegistryUpdateException("unsupported registry format version $version")

    /** An entry has a value outside its rules, such as decimals above 36 or a malformed contract address. */
    class InvalidEntry(message: String) : RegistryUpdateException(message)

    /** The package adds an entry that already exists: in the baked catalog, or earlier in the same package. */
    class Conflict(message: String) : RegistryUpdateException(message)

    /** The sequence is not higher than the installed package's, so installing it would roll the catalog back. */
    class NotNewer(val installed: Long, val offered: Long) :
        RegistryUpdateException("package sequence $offered is not newer than installed sequence $installed")
}
