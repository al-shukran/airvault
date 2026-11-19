package app.airvault.chains.solana.signer

/**
 * Parses [bytes] as a legacy message or a v0 message, and checks every length and index against the message and its lookups.
 * Throws [MalformedSolanaMessageException] for anything that is not a well-formed message.
 */
fun parseSolanaMessage(bytes: ByteArray): SolanaMessage {
    if (bytes.size > SolanaMessage.MAX_BYTES) {
        malformedSolanaMessage("the message is ${bytes.size} bytes; the limit is ${SolanaMessage.MAX_BYTES}")
    }
    val reader = SolanaMessageReader(bytes)
    val version = when (val first = reader.peek("the first byte")) {
        // A legacy message has no prefix: its first byte is num_required_signatures, which is below 0x80.
        in 0x00..0x7F -> SolanaMessage.Version.LEGACY
        0x80 -> {
            reader.u8("the version prefix")
            SolanaMessage.Version.V0
        }
        else -> malformedSolanaMessage("unsupported message version ${first and 0x7F}")
    }

    val header = SolanaMessage.Header(
        numRequiredSignatures = reader.u8("the header"),
        numReadonlySignedAccounts = reader.u8("the header"),
        numReadonlyUnsignedAccounts = reader.u8("the header"),
    )
    val accountKeys = List(reader.count("the account keys", minBytesEach = 32)) {
        reader.bytes(32, "an account key")
    }
    val recentBlockhash = reader.bytes(32, "the recent blockhash")
    val instructions = List(reader.count("the instructions", minBytesEach = 3)) {
        val programIdIndex = reader.u8("an instruction's program index")
        val accountIndices = List(reader.count("an instruction's accounts", minBytesEach = 1)) {
            reader.u8("an instruction's account index")
        }
        val data = reader.bytes(reader.shortU16("an instruction's data length"), "an instruction's data")
        SolanaMessage.CompiledInstruction(programIdIndex, accountIndices, data)
    }
    val lookups = if (version == SolanaMessage.Version.V0) {
        List(reader.count("the address table lookups", minBytesEach = 34)) {
            val tableKey = reader.bytes(32, "a lookup table key")
            val writable = List(reader.count("a lookup's writable indexes", minBytesEach = 1)) {
                reader.u8("a writable index")
            }
            val readonly = List(reader.count("a lookup's readonly indexes", minBytesEach = 1)) {
                reader.u8("a readonly index")
            }
            SolanaMessage.AddressTableLookup(tableKey, writable, readonly)
        }
    } else {
        emptyList()
    }
    if (reader.remaining > 0) {
        malformedSolanaMessage("${reader.remaining} byte(s) follow the end of the message")
    }

    checkHeader(header, staticKeys = accountKeys.size)
    val accountCount = accountKeys.size + lookups.sumOf { it.writableIndexes.size + it.readonlyIndexes.size }
    if (accountCount > MAX_ACCOUNTS) {
        malformedSolanaMessage("the message names $accountCount accounts; an account index is one byte, so the limit is $MAX_ACCOUNTS")
    }
    instructions.forEachIndexed { number, instruction ->
        checkInstruction(number, instruction, accountCount)
    }

    return SolanaMessage(version, header, accountKeys, recentBlockhash, instructions, lookups)
}

/** An account index is one byte, so no message can address more accounts than this. */
private const val MAX_ACCOUNTS = 256

/**
 * The static keys are ordered: writable signers, readonly signers, writable non-signers, then readonly non-signers. The header
 * counts must fit inside the static keys without overlapping, and the fee payer (key 0) must be a writable signer.
 */
private fun checkHeader(header: SolanaMessage.Header, staticKeys: Int) {
    if (header.numRequiredSignatures + header.numReadonlyUnsignedAccounts > staticKeys) {
        malformedSolanaMessage("the header names more accounts than the message lists as static keys")
    }
    if (header.numReadonlySignedAccounts >= header.numRequiredSignatures) {
        malformedSolanaMessage("the fee payer must be a writable signer")
    }
}

private fun checkInstruction(number: Int, instruction: SolanaMessage.CompiledInstruction, accountCount: Int) {
    if (instruction.programIdIndex == 0) {
        malformedSolanaMessage("instruction $number: the fee payer cannot be the program")
    }
    if (instruction.programIdIndex >= accountCount) {
        malformedSolanaMessage("instruction $number: program index ${instruction.programIdIndex} is out of range ($accountCount accounts)")
    }
    for (index in instruction.accountIndices) {
        if (index >= accountCount) {
            malformedSolanaMessage("instruction $number: account index $index is out of range ($accountCount accounts)")
        }
    }
}
