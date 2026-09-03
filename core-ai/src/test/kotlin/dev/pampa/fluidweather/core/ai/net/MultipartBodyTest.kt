package dev.pampa.fluidweather.core.ai.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartBodyTest {

  @Test
  fun `il preludio porta i campi e l'intestazione del file, l'epilogo chiude`() {
    val parts = MultipartBody.build(
      fields = linkedMapOf("model" to "whisper-large-v3", "language" to "it"),
      fileFieldName = "file",
      fileName = "ask.wav",
      fileMime = "audio/wav",
      boundary = "XYZ",
    )
    val expectedPrelude = "--XYZ\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3\r\n" +
      "--XYZ\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nit\r\n" +
      "--XYZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ask.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
    assertEquals(expectedPrelude, String(parts.prelude, Charsets.UTF_8))
    assertEquals("\r\n--XYZ--\r\n", String(parts.epilogue, Charsets.UTF_8))
    assertEquals("multipart/form-data; boundary=XYZ", parts.contentType)
    assertEquals(parts.prelude.size + 1000L + parts.epilogue.size, parts.contentLength(1000L))
  }

  @Test
  fun `un campo che contiene il boundary viene rifiutato`() {
    val error = runCatching {
      MultipartBody.build(mapOf("prompt" to "--XYZ--"), "file", "a.wav", "audio/wav", boundary = "XYZ")
    }.exceptionOrNull()
    assertTrue(error is IllegalArgumentException)
  }
}
