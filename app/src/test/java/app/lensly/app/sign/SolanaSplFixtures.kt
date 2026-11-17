package app.lensly.app.sign

/**
 * The SPL message vectors of `vectors/solana/spl.txt` in the test resources. The file holds the spl-* rows of
 * `shared/src/test/resources/vectors/solana/vectors.txt`, copied as they are, so the bytes are the ones solders serialized. Each row is
 * `name<TAB>hex`.
 */
internal object SolanaSplFixtures {
    private val rows: Map<String, ByteArray> by lazy {
        val stream = checkNotNull(SolanaSplFixtures::class.java.getResourceAsStream("/vectors/solana/spl.txt")) {
            "missing vectors/solana/spl.txt"
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
        checkNotNull(rows[name]) { "missing $name in vectors/solana/spl.txt" }.copyOf()

    /** The fee payer of every vector: SHA-256 of "payer", in base58. It is the one signer of each. */
    const val PAYER = "AWxggjuZRmWULwxwPeM6ZZxRtdDdekVq22mFRx2QbW7U"

    /** The real USDC mint on Solana, 6 decimals. */
    const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    /** The associated token account of [OWNER] for [USDC], derived by solders. */
    const val USDC_ATA = "8qYoe3sc4zgkEBwnbpTKtGrg81KJ7EBx7bHy7RiSbxfj"

    /** The owner of [USDC_ATA]: SHA-256 of "owner". */
    const val OWNER = "67vHA8qZGCJKw1UNGUJZME4MwEWDRGWzp7MGvsut43A8"

    /** The destination token account of the plain Transfer and of the ComputeBudget transfer: SHA-256 of "destination-2". */
    const val DESTINATION = "2yf7bSzyaMuLBigWgy6FbLThjdUbTRtQRpa3uNVvKHa9"

    /** The source token account of the USDC vectors: SHA-256 of "source-token-account". */
    const val SOURCE = "FbtVsgeFLUpwiJcw5RfHKP1B2kFjt7vvW4QxgpVyjqyi"

    /** The source token account of the plain Transfer: SHA-256 of "source-2". */
    const val PLAIN_SOURCE = "CguTsxypvu1QZCM27nHWcoA4RCCkoCQtBvu5hBSfmQuX"
}
