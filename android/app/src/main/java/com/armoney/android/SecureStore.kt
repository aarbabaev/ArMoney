package com.armoney.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted atomic record per scope; missing and unreadable are intentionally distinct. */
class SecureStore(context: Context) : PrivateStore {
    private val directory = File(context.noBackupFilesDir, "armoney").apply { check(mkdirs() || isDirectory) }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("armoney-storage-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("armoney-storage-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(scope: String) = AtomicFile(File(directory, MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }))
    @Synchronized override fun read(scope: String): String? {
        val file = file(scope)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.readFully(); require(bytes.size in 29..1_048_576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(scope.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized override fun write(scope: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(scope.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray()); require(bytes.size <= 1_048_576)
        val file = file(scope); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    @Synchronized override fun remove(scope: String) { file(scope).delete(); check(read(scope) == null) }
}
