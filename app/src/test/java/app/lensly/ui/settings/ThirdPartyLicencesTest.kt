package app.lensly.ui.settings

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ThirdPartyLicencesTest {
    @Test
    fun blocksSplitOnBlankLinesAndWrappedLinesJoin() {
        val blocks = noticeBlocks("first line\nwraps here\n\n==== Heading ====\n\nbody text")
        assertEquals(
            listOf(
                NoticeBlock("first line wraps here", heading = false),
                NoticeBlock("Heading", heading = true),
                NoticeBlock("body text", heading = false),
            ),
            blocks,
        )
    }

    @Test
    fun shortBlockEndingWithColonIsHeading() {
        assertEquals(NoticeBlock("Apache License 2.0:", heading = true), noticeBlocks("Apache License 2.0:").single())
    }

    @Test
    fun shippedNoticeKeepsEveryLicenceText() {
        // Gradle runs unit tests in the app module, so the repository's licenses/ folder is one level up.
        val notice = File("../licenses/THIRD-PARTY-NOTICES.txt").readText()
        // libsecp256k1 is shipped as a native library, and its MIT text must come with it.
        assertTrue(notice.contains("libsecp256k1-jni.so"))
        assertTrue(notice.contains("Copyright (c) 2013 Pieter Wuille"))
        assertTrue(notice.contains("Permission is hereby granted, free of charge"))
        // Each other licence the bundled libraries name.
        assertTrue(notice.contains("Version 2.0, January 2004"))
        assertTrue(notice.contains("Eclipse Public License - v 2.0"))
        assertTrue(notice.contains("Redistribution and use in source and binary forms"))
        assertTrue(notice.contains("Bouncy Castle"))
        assertTrue(notice.contains("MIT No Attribution"))
        assertTrue(notice.contains("SIL OPEN FONT LICENSE Version 1.1"))
    }

    @Test
    fun shippedNoticeParsesIntoHeadingsAndText() {
        val blocks = noticeBlocks(File("../licenses/THIRD-PARTY-NOTICES.txt").readText())
        assertTrue(blocks.any { it.heading && it.text == "Apache License 2.0" })
        assertTrue(blocks.any { it.heading && it.text == "MIT License: libsecp256k1 (Copyright (c) 2013 Pieter Wuille)" })
    }
}
