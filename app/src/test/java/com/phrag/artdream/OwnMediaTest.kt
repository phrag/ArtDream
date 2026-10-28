package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnMediaTest {

    @Test fun kindFollowsTheExtension() {
        assertEquals(OwnMedia.Kind.IMAGE, OwnMedia.kindOf("holiday.JPG"))
        assertEquals(OwnMedia.Kind.IMAGE, OwnMedia.kindOf("a.webp"))
        assertEquals(OwnMedia.Kind.VIDEO, OwnMedia.kindOf("fireplace.mp4"))
        assertEquals(OwnMedia.Kind.VIDEO, OwnMedia.kindOf("clip.WebM"))
        assertNull(OwnMedia.kindOf("notes.txt"))
        assertNull(OwnMedia.kindOf("script.sh"))
        assertNull(OwnMedia.kindOf("noextension"))
    }

    @Test fun sanitizeKeepsPlainNames() {
        assertEquals("holiday 2024.jpg", OwnMedia.sanitizeName("holiday 2024.JPG"))
        assertEquals("beach (1).mp4", OwnMedia.sanitizeName("beach (1).mp4"))
    }

    @Test fun sanitizeStripsFoldersAndTraversal() {
        assertEquals("passwd.jpg", OwnMedia.sanitizeName("../../etc/passwd.jpg"))
        assertEquals("evil.png", OwnMedia.sanitizeName("C:\\Users\\me\\evil.png"))
        assertFalse(OwnMedia.sanitizeName("a/b/../c.mp4")!!.contains('/'))
        assertFalse(OwnMedia.sanitizeName("..\\..\\x.mp4")!!.contains('\\'))
    }

    @Test fun sanitizeReplacesOddCharacters() {
        assertEquals("a_b.jpg", OwnMedia.sanitizeName("a:b*?.jpg"))
        assertEquals("media.jpg", OwnMedia.sanitizeName("???.jpg"))
        assertEquals("media.mp4", OwnMedia.sanitizeName(".mp4"))
    }

    @Test fun sanitizeRefusesOtherTypes() {
        assertNull(OwnMedia.sanitizeName("setup.apk"))
        assertNull(OwnMedia.sanitizeName("photo.jpg.exe"))
        assertNull(OwnMedia.sanitizeName("../x.html"))
        assertNull(OwnMedia.sanitizeName(""))
    }

    @Test fun sanitizeBoundsTheLength() {
        val name = OwnMedia.sanitizeName("x".repeat(500) + ".mp4")!!
        assertTrue(name.length <= 84)
        assertTrue(name.endsWith(".mp4"))
    }

    @Test fun limits() {
        assertEquals("2.5 GB", OwnMedia.gb(OwnMedia.MAX_FILE_BYTES))
        assertEquals(2_684_354_560L, OwnMedia.MAX_FILE_BYTES)
        assertEquals("8 GB", OwnMedia.gb(OwnMedia.QUOTA_BYTES))
        assertEquals("1 GB", OwnMedia.gb(OwnMedia.MIN_FREE_BYTES))
        assertTrue("a 2.5 GB file fits next to a full earlier one", 2 * OwnMedia.MAX_FILE_BYTES <= OwnMedia.QUOTA_BYTES)
    }

    @Test fun roomNeedsQuotaAndFreeSpace() {
        val gb = 1024L * 1024 * 1024
        val big = OwnMedia.MAX_FILE_BYTES
        assertNull(OwnMedia.roomProblem(used = 0, size = big, free = 5 * gb))
        assertNull(OwnMedia.roomProblem(used = 2 * gb, size = big, free = big + gb))
        assertEquals("Not enough free space on the TV (3 GB free, and it keeps 1 GB spare)",
            OwnMedia.roomProblem(used = 0, size = big, free = 3 * gb))
        assertEquals("That would go over the 8 GB limit for your own media",
            OwnMedia.roomProblem(used = 6 * gb, size = big, free = 100 * gb))
    }
}
