package app.lensly.core.btc

/** The owner's rule for Bitcoin input amounts. It applies to every online side alike. */
enum class InputVerificationPolicy(
    /** The code the vault stores this policy as. A stored code never changes. */
    val wire: Int,
) {
    /** Every input carries its previous transaction (non_witness_utxo), and every input amount is verified from it. */
    STRICT(0),

    /**
     * Segwit inputs may carry only a witness_utxo. Their amounts are unverified, the review says so, and the owner must
     * confirm them before signing. Legacy inputs still need their previous transaction.
     */
    SEGWIT_WITNESS_UTXO_WITH_WARNING(1),

    /**
     * Legacy inputs need their previous transaction. Segwit inputs may carry only a witness_utxo, with no extra
     * confirmation. Their amounts are still shown as unverified.
     */
    LEGACY_STRICT_SEGWIT_RELAXED(2);

    companion object {
        /** The policy stored as [wire], or null for a code this version does not know. */
        fun ofWire(wire: Int): InputVerificationPolicy? = entries.firstOrNull { it.wire == wire }
    }
}

/** The owner's rule for which online side may ask for a Bitcoin signature. */
enum class SigningSourcePolicy(
    /** The code the vault stores this policy as. A stored code never changes. */
    val wire: Int,
) {
    /** Any online side that reads and writes a code format the vault decodes. */
    ANY(0),

    /** Only a PSBT the Companion App marked with [BtcPsbt.withCompanionMarker] may be signed. */
    COMPANION_ONLY(1);

    companion object {
        /** The policy stored as [wire], or null for a code this version does not know. */
        fun ofWire(wire: Int): SigningSourcePolicy? = entries.firstOrNull { it.wire == wire }
    }
}
