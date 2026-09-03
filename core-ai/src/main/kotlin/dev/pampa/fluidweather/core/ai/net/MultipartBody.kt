package dev.pampa.fluidweather.core.ai.net

import java.io.ByteArrayOutputStream

/**
 * Il corpo `multipart/form-data` a mano, come gia' fa Fluid transit col microfono: i campi di
 * testo e l'intestazione del file in un preludio, la chiusura in un epilogo, il file nel mezzo
 * copiato a flusso. Cosi' `Content-Length` e' esatto senza tenere l'audio in memoria due volte.
 */
object MultipartBody {

  class Parts(val prelude: ByteArray, val epilogue: ByteArray, val contentType: String) {
    fun contentLength(fileBytes: Long): Long = prelude.size + fileBytes + epilogue.size
  }

  fun build(
    fields: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    fileMime: String,
    boundary: String = "----FluidWeather${System.nanoTime()}",
  ): Parts {
    require(fields.none { boundary in it.key || boundary in it.value }) { "boundary presente nei campi" }
    val prelude = ByteArrayOutputStream()
    fields.forEach { (name, value) ->
      prelude.write(
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8),
      )
    }
    prelude.write(
      (
        "--$boundary\r\nContent-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n" +
          "Content-Type: $fileMime\r\n\r\n"
        ).toByteArray(Charsets.UTF_8),
    )
    val epilogue = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    return Parts(prelude.toByteArray(), epilogue, "multipart/form-data; boundary=$boundary")
  }
}
