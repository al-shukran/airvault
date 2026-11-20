package app.lensly.core.ur

/**
 * The first map key that a sign request reserves for Lumio labels and bookkeeping. A request parser ignores such a key, since it never
 * changes the signed bytes or the review. Every other unknown key is still refused. See "Frozen Lensly contract" in
 * docs/creating-airvault-apps.md.
 */
const val RESERVED_KEYS_FROM = 1000L
