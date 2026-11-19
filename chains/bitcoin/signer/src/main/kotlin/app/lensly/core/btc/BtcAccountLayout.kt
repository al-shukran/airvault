package app.lensly.core.btc

/**
 * The layout of a Bitcoin Account that the signer and the Account share: the gap limit, and the branch of receive and change addresses.
 * BtcAccount re-exports them, and the signer reads them from here, because this module cannot see BtcAccount.
 */
object BtcAccountLayout {
    /** The standard gap limit: the run of empty addresses a restore scan accepts before it stops. */
    const val GAP_LIMIT = 20

    /** Branch 0: receive addresses. */
    const val RECEIVE = 0

    /** Branch 1: change addresses. */
    const val CHANGE = 1
}
