package dev.pampa.fluidweather.core.ai.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra e decifra un segreto. Esiste come interfaccia perche' il Keystore non c'e' sul computer:
 * i test lo sostituiscono con una versione trasparente, e il resto del codice non se ne accorge.
 */
interface SecretCipher {
  fun encrypt(plain: String): String

  /** Null quando il blob non si puo' leggere: chiave del Keystore cambiata, dato corrotto. */
  fun decrypt(blob: String): String?
}

/**
 * AES-256-GCM con la chiave dentro l'Android Keystore: e' quello che `EncryptedSharedPreferences`
 * faceva prima di essere deprecata (security-crypto 1.1.0-alpha07, aprile 2025: "usate
 * direttamente il Keystore"). Ottanta righe, nessuna dipendenza, stesso livello di protezione.
 *
 * Formato del blob: `v1:` + base64(iv[12] || testo cifrato || tag). L'IV lo sceglie il Keystore a
 * ogni cifratura (`setRandomizedEncryptionRequired`), quindi due salvataggi della stessa chiave
 * non si assomigliano.
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

  override fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    val iv = cipher.iv
    val blob = ByteArray(iv.size + encrypted.size)
    iv.copyInto(blob)
    encrypted.copyInto(blob, iv.size)
    return PREFIX + Base64.getEncoder().encodeToString(blob)
  }

  override fun decrypt(blob: String): String? {
    if (!blob.startsWith(PREFIX)) return null
    return try {
      val bytes = Base64.getDecoder().decode(blob.removePrefix(PREFIX))
      if (bytes.size <= IV_BYTES) return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
      String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: AEADBadTagException) {
      null
    } catch (e: KeyPermanentlyInvalidatedException) {
      null
    } catch (e: UnrecoverableKeyException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return generator.generateKey()
  }

  companion object {
    const val DEFAULT_ALIAS = "fluidweather.ai"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
  }
}
