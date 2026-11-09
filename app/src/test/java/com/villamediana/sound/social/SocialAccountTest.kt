package com.villamediana.sound.social

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The phone's profile against the real relay: made by itself, kept across launches, edited, deleted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class SocialAccountTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun account(base: String) = SocialAccount(context, scope) { token -> RelayApi(LocalRelay.http, base, token) }

    @Test
    fun theProfileLivesOnThisPhoneAndComesBackByCode() = runBlocking {
        val base = LocalRelay.base()
        val first = account(base)
        val made = first.ensure()!!
        assertEquals(AccountStatus.READY, first.status.value)
        val code = first.recoveryCode.value!!
        assertTrue(code.matches(Regex("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}")))
        // Next launch: the same profile, nothing new made.
        val again = account(base)
        assertEquals(made.id, again.ensure()!!.id)
        // Edited.
        val renamed = again.rename("Zé Teste", null).getOrThrow()
        assertEquals("Zé Teste", renamed.name)
        assertEquals("Zé Teste", again.me.value!!.name)
        // A new code replaces the old.
        val fresh = again.newRecoveryCode().getOrThrow()
        assertNotEquals(code, fresh)
        // Deleted: none made again by itself; asked for, a new one.
        again.delete {}.getOrThrow()
        assertEquals(AccountStatus.OPTED_OUT, again.status.value)
        assertNull(account(base).ensure())
        val newOne = account(base).ensure(force = true)!!
        assertNotEquals(made.id, newOne.id)
    }

    @Test
    fun recoveringReplacesTheEmptyProfileThisPhoneMade() = runBlocking {
        val base = LocalRelay.base()
        // The profile on the "old phone".
        val old = RelayApi(LocalRelay.http, base) { null }.createAccount()
        // This phone: its own profile first, then the code.
        val phone = account(base)
        phone.ensure(force = true)!!
        val auto = phone.me.value!!
        val got = phone.recover(old.recoveryCode.lowercase().replace("-", " ")).getOrThrow()
        assertEquals(old.me.id, got.id)
        assertEquals(old.recoveryCode, phone.recoveryCode.value)
        // The empty automatic one is gone from the relay.
        val e = runCatching { RelayApi(LocalRelay.http, base) { old.token }.user(auto.id) }.exceptionOrNull() as RelayException
        assertEquals(404, e.status)
        // A wrong code says so and keeps the profile.
        val wrong = phone.recover("AAAA-BBBB-CCCC-DDDD").exceptionOrNull() as RelayException
        assertEquals(404, wrong.status)
        assertEquals(old.me.id, phone.me.value!!.id)
    }

    @Test
    fun anOddAnswerIsAFailureNotACrash() {
        // 200 with something else than a profile (a proxy page, a changed relay…).
        val odd = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok")
                .body("<html>oi</html>".toResponseBody(null)).build()
        }.build()
        val e = runCatching { RelayApi(odd, "https://relay.test") { "x" }.me() }.exceptionOrNull()
        assertTrue(e is RelayException)
        assertEquals("bad_response", (e as RelayException).code)
    }

    @Test
    fun codesAreShownInTheRelaysShape() {
        assertEquals("7K2Q-M9XD-4TFA-H3RB", SocialAccount.formatCode("7k2q m9xd 4tfa h3rb"))
        assertEquals("7K2Q-M9XD-4TFA-H3RB", SocialAccount.formatCode("7K2QM9XD4TFAH3RB"))
    }

    @Test
    fun theRelayTakesThePhotoAsThisPhoneMakesIt() = runBlocking {
        val base = LocalRelay.base()
        val phone = account(base)
        phone.ensure(force = true)!!
        val src = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        for (y in 0 until 700 step 7) for (x in 0 until 900) src.setPixel(x, y, Color.rgb(x % 256, y % 256, 120))
        val jpeg = SocialAccount.centerSquareJpeg(src, 512, 85)
        val api = RelayApi(LocalRelay.http, base) { phone.currentToken() }
        val p = api.setAvatar(jpeg)
        // What the friends download: the same 512 px picture.
        val bytes = LocalRelay.http.newCall(okhttp3.Request.Builder().url(api.absolute(p.avatar)!!).build()).execute().use { it.body!!.bytes() }
        val back = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(512, back.width)
        assertEquals(512, back.height)
    }

    @Test
    fun aPhotoBecomesItsMiddleSquareUntrimmed() {
        // A dark portrait with black rows top and bottom (a cover would lose them; a face keeps them).
        val src = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        for (y in 300 until 700) for (x in 0 until 600) src.setPixel(x, y, Color.rgb(200, 150, 120))
        val jpeg = SocialAccount.centerSquareJpeg(src, 512, 85)
        val out = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
        // The middle square (rows 200..800 of the original): black at its top edge, skin in the middle.
        val top = out.getPixel(256, 5)
        val mid = out.getPixel(256, 256)
        assertTrue(Color.red(top) < 40)
        assertTrue(Color.red(mid) > 150)
    }
}
