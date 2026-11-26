package app.lensly.app

import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultRecordNames
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * What a decoy session shows for the wrong password lockout, which lives outside its vault and belongs to the real one. A decoy never
 * writes it and never reads it. A change made in a decoy is kept in the decoy's own vault record, so the screen shows the result a real
 * vault shows, in this session and in every later one. Before any change it shows the defaults. The tries-left warning counts the
 * session's wrong tries against the erase count shown here (see [VaultHolder.triesBeforeErase]).
 *
 * The Hidden Entry Gesture is not kept here. The camera reads it while the vault is locked, when no decoy record can be opened, so it is
 * one device-wide setting that a decoy changes as a real vault does (see [app.lensly.ui.flow.EntryGestureChangePorts]).
 *
 * [VaultHolder] opens it for each new session and clears it on every lock. It holds nothing in a real session.
 *
 * The record is a version byte (2), the cap in milliseconds (a long, -1 for Default), and the erase count (an int). A missing or damaged
 * record, or one of another version, reads as no change made.
 */
class DecoyShown {
    private var session: VaultSession? = null

    /** The lockout cap in milliseconds the decoy shows, or null for Default. */
    var failureCapMs: Long? = null
        private set

    /** The erase count the decoy shows. */
    var failureEraseAfter: Int = FailureDelay.DEFAULT_WIPE_AFTER
        private set

    /** Keeps the lockout the decoy shows. Returns false when it could not be kept, and the decoy then shows what it showed before. */
    fun showFailureLimits(capMs: Long?, eraseAfter: Int): Boolean {
        val own = session ?: return false
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(VERSION)
            out.writeLong(capMs ?: -1L)
            out.writeInt(eraseAfter)
        }
        try {
            own.putVaultRecord(RECORD, buffer.toByteArray())
        } catch (e: IOException) {
            return false
        } catch (e: IllegalStateException) {
            return false
        }
        failureCapMs = capMs
        failureEraseAfter = eraseAfter
        return true
    }

    /** Starts for [opened]: reads the decoy's record when it is a decoy session, and shows nothing otherwise. */
    internal fun open(opened: VaultSession) {
        clear()
        if (!opened.isDecoy) return
        session = opened
        val bytes = try {
            opened.getVaultRecord(RECORD)
        } catch (e: VaultException) {
            null
        } ?: return
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != VERSION) return
                failureCapMs = input.readLong().takeIf { it >= 0 }
                failureEraseAfter = input.readInt()
            }
        } catch (e: IOException) {
            clear()
            session = opened
        }
    }

    internal fun clear() {
        session = null
        failureCapMs = null
        failureEraseAfter = FailureDelay.DEFAULT_WIPE_AFTER
    }

    private companion object {
        const val RECORD = VaultRecordNames.DECOY_SHOWN
        const val VERSION = 2
    }
}
