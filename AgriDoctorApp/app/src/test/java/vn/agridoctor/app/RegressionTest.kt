package vn.agridoctor.app

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class RegressionTest {
    private lateinit var context: Context
    private lateinit var journal: Journal
    private val input = LookupInput(Crop.COFFEE, "rỉ sắt", "cà phê vối", "mang trái", "lá", "Đắk Lắk")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE).edit().clear().commit()
        journal = Journal(context)
    }

    @Test fun usbHttpIsLimitedToDebugLoopbackAndEmulator() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            assertTrue(validEndpoint("http://$host:8765", debug = true))
            assertFalse(validEndpoint("http://$host:8765", debug = false))
        }
        for (address in listOf("http://192.168.1.2:8765", "http://0.0.0.0:8765",
                "http://localhost.example.com:8765", "http://127.0.0.1.example.com:8765",
                "http://name@127.0.0.1:8765", "http://127.0.0.1:8765?key=value",
                "http://127.0.0.1:8765#fragment")) {
            assertFalse(address, validEndpoint(address, debug = true))
        }
        assertTrue(validEndpoint("https://example.com", debug = false))
    }

    @Test fun customerBuildIgnoresTheOldUsbAddress() {
        assertEquals("https://service.example.com", serviceEndpoint("http://127.0.0.1:8765", false, "https://service.example.com"))
        assertEquals("", serviceEndpoint("http://127.0.0.1:8765", false, ""))
        assertEquals("", serviceEndpoint("", false, "http://service.example.com"))
        assertEquals("", serviceEndpoint("", false, "https://user:secret@service.example.com"))
        assertEquals("http://127.0.0.1:8765", serviceEndpoint("http://127.0.0.1:8765", true, "https://service.example.com"))
    }

    @Test fun newJournalEntryIsSeparateFromOldTestEntries() {
        val old = journal.save(input, LookupResult("Lượt thử", "Quan sát", emptyList()), true, "v1").single()
        val entries = journal.save(input, LookupResult("Lượt mới", "Quan sát", emptyList()), false, "v2")
        assertEquals(2, entries.size)
        assertTrue(entries.single { it.id == old.id }.demo)
        assertEquals("Lượt mới", entries.filterNot { it.demo }.single().headline)
        assertFalse(journal.demo)
    }

    @Test fun unapprovedContentIsExcludedWithoutChangingItsApproval() {
        val approved = Knowledge(context).cases.filter { it.id in setOf("R02", "R08") }
        val pending = approved.map { it.copy(pending = true) }
        val input = LookupInput(Crop.RICE, "bông bạc", "", "", "", "")
        assertTrue(Symptoms.lookup(input, pending, false).cases.isEmpty())
        assertEquals(setOf("R02", "R08"), Symptoms.lookup(input, approved, false).cases.map { it.id }.toSet())
        assertTrue(pending.all { it.pending })
    }

    @Test fun android23GateStillRequiresHttpsForUsb() {
        val originalSdk = android.os.Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 23)
            assertFalse(validEndpoint("http://127.0.0.1:8765", debug = true))
            assertFalse(validEndpoint("http://10.0.2.2:8765", debug = true))
            assertTrue(validEndpoint("https://example.com", debug = false))
        } finally {
            ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
    }

    @Test fun journalRetainsContextAndReadsLegacyRows() {
        journal.save(input, LookupResult("Kết quả", "Quan sát", emptyList()), true, "1.3:hash")
        val saved = Journal(context).entries().single()
        assertEquals(input.kind, saved.kind)
        assertEquals(input.stage, saved.stage)
        assertEquals(input.part, saved.part)
        assertEquals(input.region, saved.region)
        assertEquals("1.3:hash", saved.knowledgeVersion)
        assertEquals(LOOKUP_ALGORITHM_VERSION, saved.algorithmVersion)
        val prefs = context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE)
        val old = JSONArray(prefs.getString("scans", "[]")).getJSONObject(0)
        listOf("kind", "stage", "part", "region", "knowledge_version", "algorithm_version").forEach(old::remove)
        prefs.edit().putString("scans", JSONArray().put(old).toString()).commit()
        assertEquals("", journal.entries().single().kind)
    }

    @Test fun oneBrokenRowDoesNotEraseValidHistory() {
        journal.save(input, LookupResult("Kết quả", "Quan sát", emptyList()), true, "v1")
        val prefs = context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("scans", "[]")).put(JSONObject().put("crop", "UNKNOWN"))
        val damaged = array.toString()
        prefs.edit().putString("scans", damaged).commit()
        assertEquals(1, journal.entries().size)
        journal.save(input, LookupResult("Lượt mới", "Quan sát", emptyList()), true, "v2")
        assertEquals(2, journal.entries().size)
        assertTrue(File(context.noBackupFilesDir, "journal-recovery").listFiles()!!.any { it.readText() == damaged })
        assertNotNull(journal.recoveryNotice)
    }

    @Test fun malformedRootIsPreservedBeforeNextSave() {
        val prefs = context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE)
        prefs.edit().putString("scans", "{broken").commit()
        journal.save(input, LookupResult("Kết quả", "Quan sát", emptyList()), true, "v1")
        assertEquals(1, journal.entries().size)
        assertTrue(File(context.noBackupFilesDir, "journal-recovery").listFiles()!!.any { it.readText() == "{broken" })
    }

    @Test fun blockedRecoveryDoesNotOverwriteOriginal() {
        val directory = File(context.noBackupFilesDir, "journal-recovery")
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        directory.writeText("block")
        val prefs = context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE)
        prefs.edit().putString("scans", "{broken").commit()
        try {
            journal.save(input, LookupResult("Kết quả", "Quan sát", emptyList()), true, "v1")
            fail("Must block writing if recovery backup cannot be made")
        } catch (_: IllegalStateException) {
            assertEquals("{broken", prefs.getString("scans", ""))
        } finally { directory.delete() }
    }

    @Test fun changedOrCorruptKnowledgeIsReplacedFromAsset() {
        val target = File(context.filesDir, "knowledge-v1.3.sqlite")
        target.writeText("obsolete database")
        val first = Knowledge(context)
        assertEquals(80, first.cases.size)
        assertTrue(first.version.startsWith("1.3:"))
        val expected = context.assets.open("agridoctor.sqlite").use { it.readBytes() }
        assertArrayEquals(expected, target.readBytes())
        target.writeText("damaged after install")
        val second = Knowledge(context)
        assertEquals(80, second.cases.size)
        assertEquals(first.version, second.version)
        assertArrayEquals(expected, target.readBytes())
    }

    @Test fun negationUncertaintyAndConflictingSignsRequireConfirmation() {
        val catalog = Knowledge(context).cases
        for (text in listOf("hổng thấy bông bạc", "hình như bông bạc", "bông bạc và không có bông bạc", "bông bạc, à không có bông bạc")) {
            val result = Symptoms.lookup(LookupInput(Crop.RICE, text, "", "", "", ""), catalog, true)
            assertTrue(text, result.cases.isEmpty())
            assertEquals(text, "Cần xác nhận dấu hiệu", result.headline)
        }
        val positive = Symptoms.lookup(LookupInput(Crop.RICE, "không thấy sâu nhưng bông bạc", "", "", "", ""), catalog, true)
        assertEquals(setOf("R02", "R08"), positive.cases.map { it.id }.toSet())
        val sap = Symptoms.lookup(LookupInput(Crop.DURIAN, "gốc sầu riêng rỉ mủ", "", "", "", ""), catalog, true)
        assertEquals(listOf("D02"), sap.cases.map { it.id })
    }

    @Test fun postposedNegationIsHandledWithoutChangingPositiveMatches() {
        val catalog = Knowledge(context).cases
        for (text in listOf("bông bạc thì không có", "bông bạc không thấy", "bông bạc chưa xuất hiện", "bông bạc thì hông có đâu")) {
            val result = Symptoms.lookup(LookupInput(Crop.RICE, text, "", "", "", ""), catalog, true)
            assertTrue(text, result.cases.isEmpty())
            assertEquals("Cần xác nhận dấu hiệu", result.headline)
        }
        for (text in listOf("bông bạc", "bông bạc thì có", "bông bạc không chỉ xuất hiện trên lá")) {
            val result = Symptoms.lookup(LookupInput(Crop.RICE, text, "", "", "", ""), catalog, true)
            assertEquals(text, setOf("R02", "R08"), result.cases.map { it.id }.toSet())
        }
    }

    @Test fun duplicateAndBlankHistoryIdsAreRecoveredBeforeRendering() {
        val first = journal.save(input, LookupResult("Đầu tiên", "Quan sát", emptyList()), true, "v1").single()
        journal.save(input, LookupResult("Khác", "Quan sát", emptyList()), true, "v1")
        val prefs = context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("scans", "[]"))
        val duplicate = JSONObject(array.getJSONObject(1).toString()).put("headline", "Trùng")
        val blank = JSONObject(duplicate.toString()).put("id", " ")
        val damaged = array.put(duplicate).put(blank).toString()
        prefs.edit().putString("scans", damaged).commit()
        val recovered = journal.entries()
        assertEquals(2, recovered.size)
        assertEquals(2, recovered.map { it.id }.distinct().size)
        assertEquals("Đầu tiên", recovered.single { it.id == first.id }.headline)
        assertNotNull(journal.recoveryNotice)
        assertTrue(File(context.noBackupFilesDir, "journal-recovery").listFiles()!!.any { it.readText() == damaged })
        journal.save(input, LookupResult("Mới", "Quan sát", emptyList()), true, "v1")
        assertEquals(3, journal.entries().size)
    }

    @Test fun exifRotationIsAppliedBeforeResizeOnOldAndroid() {
        val file = File(context.cacheDir, "orientation.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val rotated = photoBitmap(context, Uri.fromFile(file), 100)
        assertEquals(20, rotated.width)
        assertEquals(40, rotated.height)
        rotated.recycle()
        file.delete()
    }
}
