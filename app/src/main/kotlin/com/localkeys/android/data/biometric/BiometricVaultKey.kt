package com.localkeys.android.data.biometric

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chave AES/GCM que vive no Android Keystore e só se liberta após autenticação
 * biométrica — o equivalente Android do "desbloqueio rápido" com keyring do
 * desktop. Cifra a chave derivada do vault (32 bytes) que fica no DataStore:
 * sem a biometria do dono, ninguém a recupera.
 *
 * Fluxo (padrão do BiometricPrompt):
 *  1. `createEncryptCipher()` / `createDecryptCipher()` inicializam um Cipher
 *     vinculado à chave do Keystore (o IV de 12 bytes vai na frente do ct).
 *  2. O Cipher é passado como `CryptoObject` ao BiometricPrompt.
 *  3. Após `onAuthenticationSucceeded`, `wrapWithCipher`/`unwrapWithCipher`
 *     rodam o `doFinal` — só funciona agora, autenticado.
 *
 * O IV + tag são obrigatórios no GCM (tag no fim do ct, 128 bits). Cadastrar um
 * novo dedo/rosto invalida a chave (`setInvalidatedByBiometricEnrollment`) — o
 * app pede a senha de novo, que gera uma chave de Keystore nova.
 */
class BiometricVaultKey(private val alias: String = KEY_ALIAS) {

    /** Cipher em modo ENCRYPT — passe ao BiometricPrompt como CryptoObject. */
    fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        return cipher
    }

    /** Cifra a chave do vault após o sucesso da biometria. Formato `iv || ct`. */
    fun wrapWithCipher(plaintext: ByteArray, cipher: Cipher): ByteArray {
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    /** Cipher em modo DECRYPT para o cofre guardado (`iv || ct`). */
    fun createDecryptCipher(wrapped: ByteArray): Cipher {
        require(wrapped.size > GCM_IV_LEN + GCM_TAG_LEN) { "cofre biométrico corrompido" }
        val iv = wrapped.copyOfRange(0, GCM_IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher
    }

    /** Recupera a chave do vault após o sucesso da biometria. */
    fun unwrapWithCipher(wrapped: ByteArray, cipher: Cipher): ByteArray =
        cipher.doFinal(wrapped, GCM_IV_LEN, wrapped.size - GCM_IV_LEN)

    /** Apaga a chave do Keystore (desativar desbloqueio biométrico). */
    fun delete() {
        val ks = keyStore()
        ks.deleteEntry(alias)
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val purpose = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val builder = KeyGenParameterSpec.Builder(alias, purpose)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        // API 30+ refina: biometria forte, sem timeout (0 = sempre requer o sensor).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "localkeys_biometric_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_LEN = GCM_TAG_BITS / 8
    }
}
