package app.lensly.core.solana

/**
 * The messages in `vectors/solana/vectors.txt`, generated with solders 0.29.0 on Python 3.12.0 (see `vectors/solana/README.md`).
 * The base58 keys below are solders' own output for the same labels, so they do not come from the parser under test.
 */
internal object SolanaVectors {
    private val rows: Map<String, ByteArray> by lazy {
        val stream = checkNotNull(SolanaVectors::class.java.getResourceAsStream("/vectors/solana/vectors.txt")) {
            "missing vectors/solana/vectors.txt"
        }
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { row ->
                val (name, hex) = row.split('\t')
                name to ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            }
        }
    }

    /** A fresh copy of the named message, so a test can change its bytes. */
    operator fun get(name: String): ByteArray =
        checkNotNull(rows[name]) { "missing $name in vectors/solana/vectors.txt" }.copyOf()

    const val PAYER = "AWxggjuZRmWULwxwPeM6ZZxRtdDdekVq22mFRx2QbW7U"
    const val RECIPIENT = "7tark5iZaRrMfGKtKy1aqpGuRgoxbE6ec7Z5Qa4Jc5xr"
    const val TABLE = "CUDp5gcuAm64fAXUdWviywAdmrnTbrMbFPmP5u7tUL8q"
    const val UNKNOWN = "9nZkbmWvVmz17UgDtqP4ZDRHzYuTEpPRkFnZVoeMR3pM"
    const val SOURCE = "FbtVsgeFLUpwiJcw5RfHKP1B2kFjt7vvW4QxgpVyjqyi"
    const val ATA_DST = "G85scHJkVdoZQhXqVgoC9wx9dTPj9r1ph5gPo8rfFtns"
    const val OWNER = "67vHA8qZGCJKw1UNGUJZME4MwEWDRGWzp7MGvsut43A8"
    const val MINT = "9Nk2VWyuXGLSs5mNBYhgVpUATEYovXP2GLWBStSwH4UQ"
    const val ATA22 = "8dKsy9ECTqRAooTGTuUPhcMF52waQynxz3ByZWWp9jgG"
    const val OWNER22 = "FzkhWYLpvh4PUFTQU1ERtXFo8poQJ84VXGmtzxjAMBbX"
    const val MINT22 = "5UHrBSdVTrUZxAwCiVT876romMWawWhnDtGrz8jX2XJ6"
    const val SRC2 = "CguTsxypvu1QZCM27nHWcoA4RCCkoCQtBvu5hBSfmQuX"
    const val DST2 = "2yf7bSzyaMuLBigWgy6FbLThjdUbTRtQRpa3uNVvKHa9"
    const val BLOCKHASH = "3K2oo9odsrvknS7gMzJ4eLBnjd3UG14yPuJc8DiviJqY"

    const val SYSTEM = "11111111111111111111111111111111"
    const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
    const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"

    /** The vectors that parse, in the order the tests use them. */
    val WELL_FORMED = listOf(
        "legacy-sol-transfer",
        "v0-sol-transfer-alt",
        "v0-alt-readonly-raw",
        "legacy-spl-checked-ata-compute",
        "v0-token-programs",
        "legacy-unknown-program",
        "legacy-max-size",
    )
}
