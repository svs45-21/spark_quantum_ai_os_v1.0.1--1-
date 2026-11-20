package com.spark.companion

import com.spark.companion.brain.ImageGen
import com.spark.companion.brain.ImageMaker
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.ModelUnavailableException
import com.spark.companion.brain.OpenAiImageGen
import com.spark.companion.brain.ProviderBusyException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OpenAiImagesTest {

    @Test fun generationRequestAsksForOneTallJpeg() {
        val body = OpenAiImageGen.generationBody("gpt-image-2.6-flare", "a person", OpenAiImageGen.TALL)
        assertEquals("gpt-image-2.6-flare", body.getString("model"))
        assertEquals("1024x1536", body.getString("size"))
        assertEquals("jpeg", body.getString("output_format"))
        assertEquals(1, body.getInt("n"))
    }

    @Test fun editRequestCarriesThePhotoAsAFile() {
        val photo = byteArrayOf(1, 2, 3, 4)
        val raw = OpenAiImageGen.editBody("B", "gpt-image-2.6-sunburst", "keep my face", OpenAiImageGen.TALL, photo)
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"model\"\r\n\r\ngpt-image-2.6-sunburst\r\n"))
        assertTrue(text.contains("name=\"prompt\"\r\n\r\nkeep my face\r\n"))
        assertTrue(text.contains("name=\"image\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n\u0001\u0002\u0003\u0004\r\n--B--"))
    }

    @Test fun readsTheBase64Picture() {
        val png = byteArrayOf(9, 8, 7)
        val raw = """{"created":1,"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        assertArrayEquals(png, OpenAiImageGen.parseImage(raw))
    }

    @Test(expected = LlmException::class)
    fun noPictureIsAnError() {
        OpenAiImageGen.parseImage("""{"data":[]}""")
    }

    @Test fun errorsAreExplained() {
        assertTrue(OpenAiImageGen.errorFor(401, "{}").message!!.contains("key was refused"))
        assertTrue(OpenAiImageGen.errorFor(429, """{"error":{"code":"insufficient_quota","message":"x"}}""").message!!.contains("no credit"))
        assertTrue(OpenAiImageGen.errorFor(400, """{"error":{"code":"moderation_blocked","message":"x"}}""").message!!.contains("safety"))
        assertTrue(OpenAiImageGen.errorFor(404, """{"error":{"code":"model_not_found"}}""") is ModelUnavailableException)
        assertTrue(OpenAiImageGen.errorFor(429, """{"error":{"code":"rate_limit_exceeded"}}""") is ProviderBusyException)
        assertTrue(OpenAiImageGen.errorFor(503, "") is ProviderBusyException)
    }

    @Test fun imageModelsArePreferredFirst() {
        assertEquals(
            listOf("gpt-image-2.6-flare", "gpt-image-2.6-sunburst", "gpt-image-3"),
            OpenAiImageGen.rank(listOf("gpt-5", "gpt-image-3", "gpt-image-2.6-sunburst", "gpt-image-2.6-flare", "dall-e-3")),
        )
    }

    private class Fake(val out: ByteArray, val fail: Exception? = null) : ImageGen {
        override val canUsePhoto = true
        var calls = 0
        override suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray {
            calls++
            fail?.let { throw it }
            return out
        }
    }

    @Test fun freeServiceWhenThereIsNoOpenAiKey() = runBlocking {
        val free = Fake(byteArrayOf(1))
        val maker = ImageMaker({ "" }, OpenAiImageGen({ "" }, { "" }), free)
        assertArrayEquals(byteArrayOf(1), maker.generate("p", null))
        assertEquals(false, maker.canUsePhoto)
        assertEquals("the free image service", maker.label)
    }

    @Test(expected = ProviderBusyException::class)
    fun aBusyOpenAiIsNotReplacedWhenYourFaceIsNeeded() {
        runBlocking {
            val maker = ImageMaker({ "sk-test" }, BusyOpenAi(), Fake(byteArrayOf(1)))
            maker.generate("p", byteArrayOf(5))
        }
    }

    /** An OpenAI generator that is always busy (no network). */
    private class BusyOpenAi : ImageGenAdapter()

    private open class ImageGenAdapter : ImageGen {
        override val canUsePhoto = true
        override suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray = throw ProviderBusyException("busy")
    }
}
