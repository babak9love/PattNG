package com.v2ray.ang.core

import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.zip.DeflaterOutputStream
import kotlin.io.encoding.Base64

class PsiphonServerListTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val keyText = Base64.encode(pair.public.encoded)
    private val entries = "0 0 0 0 {\"ipAddress\":\"203.0.113.9\"}\n0 0 0 0 {\"ipAddress\":\"203.0.113.10\"}\n"

    private fun sign(data: String, key: PrivateKey = pair.private): String =
        Base64.encode(Signature.getInstance("SHA256withRSA").run { initSign(key); update(data.toByteArray()); sign() })

    private fun digestOf(text: String): String = Base64.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    /** A list the way Psiphon writes one: the package as JSON, compressed. */
    private fun pack(data: String = entries, signature: String = sign(data), keyDigest: String = digestOf(keyText)): ByteArray {
        val json = """{"data":${Gson().toJson(data)},"signingPublicKeyDigest":"$keyDigest","signature":"$signature"}"""
        return ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
    }

    @Test
    fun aListSignedWithTheKeyUnpacksToItsEntries() {
        assertEquals(entries, PsiphonServerList.unpack(pack(), keyText))
    }

    @Test
    fun aListThatIsNotPsiphonsIsRefused() {
        // Entries added after signing.
        assertThrows(IOException::class.java) { PsiphonServerList.unpack(pack(data = entries + "0 0 0 0 {}\n", signature = sign(entries)), keyText) }
        // Signed with another key that names ours.
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(IOException::class.java) { PsiphonServerList.unpack(pack(signature = sign(entries, other.private)), keyText) }
        // Naming another key.
        assertThrows(IOException::class.java) { PsiphonServerList.unpack(pack(keyDigest = digestOf("someone else")), keyText) }
        // Not a list at all, and a list of nothing.
        assertThrows(IOException::class.java) { PsiphonServerList.unpack("not a list".toByteArray(), keyText) }
        assertThrows(IOException::class.java) { PsiphonServerList.unpack(pack(data = "\n"), keyText) }
    }

    @Test
    fun theEntriesAreWrittenOncePerVersionOfTheListAndKeptWhenTheNextOneIsBroken() {
        val assets = folder.newFolder("assets")
        val work = folder.newFolder("work")
        assertNull(PsiphonServerList.entriesFile(assets, work, keyText))

        val source = File(assets, AppConfig.PSIPHON_SERVERS_DAT).apply { writeBytes(pack()) }
        val first = PsiphonServerList.entriesFile(assets, work, keyText)!!
        assertEquals(entries, first.readText())
        // The same version of the list is not unpacked again.
        first.writeText("left alone")
        assertEquals("left alone", PsiphonServerList.entriesFile(assets, work, keyText)!!.readText())

        // A new version is.
        val more = entries + "0 0 0 0 {\"ipAddress\":\"203.0.113.11\"}\n"
        source.writeBytes(pack(data = more))
        source.setLastModified(source.lastModified() + 5_000)
        assertEquals(more, PsiphonServerList.entriesFile(assets, work, keyText)!!.readText())

        // A broken replacement leaves the last good entries in place.
        source.writeBytes("garbage".toByteArray())
        source.setLastModified(source.lastModified() + 5_000)
        assertEquals(more, PsiphonServerList.entriesFile(assets, work, keyText)!!.readText())

        // Without the list there is nothing to hand on.
        source.delete()
        assertNull(PsiphonServerList.entriesFile(assets, work, keyText))
    }
}
