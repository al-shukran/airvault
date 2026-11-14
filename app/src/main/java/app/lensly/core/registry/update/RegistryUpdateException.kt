package app.lensly.core.registry.update

/** Why a registry package was refused. Each reason has its own class, so a caller can show a specific message. */
sealed class RegistryUpdateException(message: String) : Exception(message) {
    /** Not a registry package: wrong magic or lengths, over the size cap, truncated, or unreadable. */
    class Malformed(message: String) : RegistryUpdateException(message)

    /** The key the package names verifies neither the payload nor the header: the file was altered after signing. */
    class BadSignature : RegistryUpdateException("the signature does not verify with the key the package names")

    /** The package names a signing key this build does not trust. [keyId] is 16 hexadecimal characters. */
    class UnknownKey(val keyId: String) : RegistryUpdateException("signing key $keyId is not trusted by this app")

    /** The package names a signing key that is listed as revoked. [keyId] is 16 hexadecimal characters. */
    class RevokedKey(val keyId: String) : RegistryUpdateException("signing key $keyId has been revoked")

    /** The package needs a newer catalog schema than this app interprets. */
    class Incompatible(val required: Long, val supported: Long) :
        RegistryUpdateException("package needs catalog schema $required; this app reads up to $supported")

    /** The package is in a registry format version this app does not read: [version] is the one it states. */
    class UnsupportedFormat(val version: Long, val supported: Long) :
        RegistryUpdateException("registry format version $version is not supported; this app reads version $supported")

    /** An entry has a value outside its rules, such as decimals above 36 or a malformed contract address. */
    class InvalidEntry(message: String) : RegistryUpdateException(message)

    /** The package adds an entry that already exists: in the baked catalog, or earlier in the same package. */
    class Conflict(message: String) : RegistryUpdateException(message)

    /** The sequence is not higher than the last accepted one, so installing it would roll the catalog back. */
    class NotNewer(val installed: Long, val offered: Long) :
        RegistryUpdateException("package sequence $offered is not newer than installed sequence $installed")

    /**
     * The installed package cannot be read and no accepted sequence is recorded beside it, so no package can be shown to be
     * newer. Nothing is installed rather than assuming there was no earlier package.
     */
    class SequenceUnknown :
        RegistryUpdateException("the installed package is unreadable and its sequence is not recorded")
}
