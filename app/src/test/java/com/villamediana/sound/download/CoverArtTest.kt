package com.villamediana.sound.download

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import com.villamediana.sound.data.db.AlbumKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverArtTest {
    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    @Test fun wideImageIsCroppedToItsCenterSquare() {
        // 1600x900: red sides, blue center 900x900.
        val src = png(1600, 900, Color.RED, Rect(350, 0, 1250, 900) to Color.BLUE)
        val out = decode(squareJpeg(src)!!)
        assertEquals(900, out.width)
        assertEquals(900, out.height)
        assertTrue(out.getPixel(450, 450).isBluish())
        assertTrue(out.getPixel(3, 3).isBluish())
        assertTrue(out.getPixel(896, 896).isBluish())
    }

    @Test fun tallImageIsCroppedToo() {
        val src = png(500, 800, Color.RED, Rect(0, 150, 500, 650) to Color.GREEN)
        val out = decode(squareJpeg(src)!!)
        assertEquals(500, out.width)
        assertEquals(500, out.height)
        assertTrue(out.getPixel(3, 3).isGreenish())
        assertTrue(out.getPixel(496, 496).isGreenish())
    }

    @Test fun bigArtworkIsCappedAt1000() {
        val out = decode(squareJpeg(png(3000, 2000, Color.BLUE))!!)
        assertEquals(1000, out.width)
        assertEquals(1000, out.height)
        val square = decode(squareJpeg(png(1400, 1400, Color.BLUE))!!)
        assertEquals(1000, square.width)
    }

    @Test fun smallImagesAreNotUpscaled() {
        val out = decode(squareJpeg(png(120, 90, Color.BLUE))!!)
        assertEquals(90, out.width)
        assertEquals(90, out.height)
    }

    @Test fun letterboxBarsAreTrimmed() {
        val src = png(480, 360, Color.BLACK, Rect(0, 45, 480, 315) to Color.GREEN)
        val out = decode(squareJpeg(src)!!)
        assertEquals(270, out.width)
        assertTrue(out.getPixel(1, 1).isGreenish())
        assertTrue(out.getPixel(268, 268).isGreenish())
    }

    @Test fun darkArtworkWithABarOnOneSideIsLeftAlone() {
        // Black band only at the top: part of the artwork, not a letterbox.
        val src = png(400, 400, Color.WHITE, Rect(0, 0, 400, 80) to Color.BLACK)
        val out = decode(squareJpeg(src)!!)
        assertEquals(400, out.width)
        val top = out.getPixel(200, 10)
        assertTrue(Color.red(top) < 40 && Color.green(top) < 40 && Color.blue(top) < 40)
    }

    @Test fun notAnImage() {
        assertNull(squareJpeg("<html>nope</html>".toByteArray()))
    }

    @Test fun albumCoverNameIgnoresCaseAndSpaces() {
        val a = CoverArt.albumCoverName(AlbumKey("Soda Stereo", "Doble Vida"))
        val b = CoverArt.albumCoverName(AlbumKey(" soda stereo ", "DOBLE VIDA"))
        val c = CoverArt.albumCoverName(AlbumKey("Soda Stereo", "Signos"))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertTrue(a.startsWith("album_"))
        assertEquals(a, com.villamediana.sound.data.Storage.safe(a)) // already a safe file name
    }
}
