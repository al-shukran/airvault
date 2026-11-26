package app.lensly.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultRecordNames
import app.lensly.core.vault.VaultSession
import app.lensly.ui.camera.EntryGesture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * What a decoy session shows for the settings that live outside its vault and belong to the real one: the Hidden Entry Gesture and the
 * wrong password lockout. A decoy never writes them and never reads the real lockout. A change made in a decoy is kept in the decoy's own
 * vault record, so the screen shows the result a real vault shows, in this session and in every later one. Before any change it shows the
 * defaults for the lockout, and the camera's current gesture for the gesture, which is the one the owner just used to enter.
 *
 * [VaultHolder] opens it for each new session and clears it on every lock. It holds nothing in a real session.
 *
 * The record is a version byte (1), the gesture's stored id (0 for none), the cap in milliseconds (a long, -1 for Default), and the
 * erase count (an int). A missing or damaged record, or one of another version, reads as no change made.
 */
class DecoyShown {
    private var session: VaultSession? = null
    private var gesture by mutableStateOf<EntryGesture?>(null)

    /** The lockout cap in milliseconds the decoy shows, or null for Default. */
    var failureCapMs: Long? = null
        private set

    /** The erase count the decoy shows. */
    var failureEraseAfter: Int = FailureDelay.DEFAULT_WIPE_AFTER
        private set

    /** The gesture the decoy shows as current: its own choice, or [real] while it has made none. */
    fun entryGesture(real: EntryGesture): EntryGesture = gesture ?: real

    /** Keeps [chosen] as the decoy's gesture. Returns false when it could not be kept, and the decoy then shows what it showed before. */
    fun showEntryGesture(chosen: EntryGesture): Boolean = keep(chosen, failureCapMs, failureEraseAfter)

    /** Keeps the lockout the decoy shows. Returns false when it could not be kept, and the decoy then shows what it showed before. */
    fun showFailureLimits(capMs: Long?, eraseAfter: Int): Boolean = keep(gesture, capMs, eraseAfter)

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
                val chosen = input.readUnsignedByte().takeIf { it != 0 }?.let(EntryGesture::fromStoredId)
                val cap = input.readLong().takeIf { it >= 0 }
                gesture = chosen
                failureCapMs = cap
                failureEraseAfter = input.readInt()
            }
        } catch (e: IOException) {
            clear()
            session = opened
        }
    }

    internal fun clear() {
        session = null
        gesture = null
        failureCapMs = null
        failureEraseAfter = FailureDelay.DEFAULT_WIPE_AFTER
    }

    private fun keep(chosen: EntryGesture?, capMs: Long?, eraseAfter: Int): Boolean {
        val own = session ?: return false
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(VERSION)
            out.writeByte(chosen?.storedId ?: 0)
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
        gesture = chosen
        failureCapMs = capMs
        failureEraseAfter = eraseAfter
        return true
    }

    private companion object {
        const val RECORD = VaultRecordNames.DECOY_SHOWN
        const val VERSION = 1
    }
}
