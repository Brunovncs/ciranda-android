package app.ciranda.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key the engine seals secrets at rest with (sign-ins, the private conversations' identity),
 * as the desktop does with the system's keychain. It is random, and kept wrapped by a key of the
 * Android Keystore that never leaves it. Without a Keystore that answers, nothing is sealed on
 * this run, as before.
 */
object SealKey {
    private const val ALIAS = "ciranda-secrets"
    private const val PREFS = "ciranda-seal"
    private const val STORED = "key"

    private fun master(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** The key, made the first time; null when the Keystore can't give it. */
    fun get(ctx: Context): ByteArray? = try {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val master = master()
        val stored = sp.getString(STORED, null)
        if (stored != null) {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, blob, 0, 12))
            cipher.doFinal(blob, 12, blob.size - 12)
        } else {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, master)
            val sealed = cipher.iv + cipher.doFinal(key)
            // Written before it is used: a key that sealed something must be there next time.
            if (!sp.edit().putString(STORED, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) error("not saved")
            key
        }
    } catch (e: Exception) {
        Log.w("ciranda", "Keystore: $e; secrets are kept as they are on this run")
        null
    }
}
