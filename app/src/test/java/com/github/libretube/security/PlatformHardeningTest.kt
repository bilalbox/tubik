package com.github.libretube.security

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import com.github.libretube.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlatformHardeningTest {

    @Test
    fun `manifest disables Android Auto Backup`() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun `network security config disables cleartext traffic and user CAs in base config`() {
        var sawBaseConfig = false
        var cleartextTrafficPermitted: String? = null
        val baseConfigCertificateSources = mutableListOf<String?>()
        var inBaseConfig = false

        RuntimeEnvironment.getApplication().resources.getXml(R.xml.network_security_config).use {
            parser ->
            walkXml(parser) { eventType, name ->
                when {
                    eventType == XmlPullParser.START_TAG && name == "base-config" -> {
                        sawBaseConfig = true
                        inBaseConfig = true
                        cleartextTrafficPermitted =
                            parser.getAttributeValue(null, "cleartextTrafficPermitted")
                    }
                    eventType == XmlPullParser.END_TAG && name == "base-config" -> {
                        inBaseConfig = false
                    }
                    eventType == XmlPullParser.START_TAG &&
                        name == "certificates" &&
                        inBaseConfig -> {
                        baseConfigCertificateSources += parser.getAttributeValue(null, "src")
                    }
                }
            }
        }

        assertTrue("Expected <base-config> in network_security_config", sawBaseConfig)
        assertEquals("false", cleartextTrafficPermitted)
        assertTrue(
            "Expected system CA anchor in <base-config>",
            "system" in baseConfigCertificateSources,
        )
        assertFalse(
            "User CAs must not be trusted in <base-config>",
            "user" in baseConfigCertificateSources,
        )
    }

    @Test
    fun `data extraction rules exclude all cloud backup domains and auth xml on device transfer`() {
        val cloudBackupExcludes = mutableListOf<Pair<String?, String?>>()
        val deviceTransferExcludes = mutableListOf<Pair<String?, String?>>()
        var currentSection: String? = null

        RuntimeEnvironment.getApplication().resources.getXml(R.xml.data_extraction_rules).use {
            parser ->
            walkXml(parser) { eventType, name ->
                when {
                    eventType == XmlPullParser.START_TAG &&
                        (name == "cloud-backup" || name == "device-transfer") -> {
                        currentSection = name
                    }
                    eventType == XmlPullParser.END_TAG && name == currentSection -> {
                        currentSection = null
                    }
                    eventType == XmlPullParser.START_TAG && name == "exclude" -> {
                        val entry =
                            parser.getAttributeValue(null, "domain") to
                                parser.getAttributeValue(null, "path")
                        when (currentSection) {
                            "cloud-backup" -> cloudBackupExcludes += entry
                            "device-transfer" -> deviceTransferExcludes += entry
                        }
                    }
                }
            }
        }

        assertTrue(
            "Expected <cloud-backup> to exclude '.'",
            cloudBackupExcludes.any { (_, path) -> path == "." },
        )
        assertEquals(
            setOf("root" to ".", "database" to ".", "sharedpref" to ".", "file" to "."),
            cloudBackupExcludes.toSet(),
        )
        assertTrue(
            "Expected <device-transfer> to exclude sharedpref auth.xml",
            ("sharedpref" to "auth.xml") in deviceTransferExcludes,
        )
    }

    private fun walkXml(
        parser: XmlResourceParser,
        onNode: (eventType: Int, name: String?) -> Unit,
    ) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            onNode(eventType, parser.name)
            eventType = parser.next()
        }
    }
}
