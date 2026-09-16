package com.yourapp.USBooter.util

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * The repair engine writes its prose in English; the web layer translates it by
 * matching each line against the regexes in assets/i18n.js. That mapping breaks
 * silently whenever a message is reworded, so this test drives the engine over
 * every damaged-drive fixture and asserts that each title and detail it emits is
 * still covered by a rule, in every language the app ships.
 */
class RepairTranslationCoverageTest {

    private val languages = listOf("it", "es", "pt", "zh")

    /** [regex, translations] pairs lifted straight out of assets/i18n.js. */
    private data class Rule(val pattern: Pattern, val languages: Set<String>)

    private fun i18nFile(): File {
        val candidates = listOf(
            File("src/main/assets/i18n.js"),
            File("app/src/main/assets/i18n.js"),
            File("USBooter_2.0/app/src/main/assets/i18n.js")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("i18n.js not found from ${File(".").absolutePath}")
    }

    private fun loadRules(): List<Rule> {
        val text = i18nFile().readText()
        val rules = mutableListOf<Rule>()
        // Rules are written as: [/^…$/,\n  { it: '…', es: '…', pt: '…', zh: '…' }],
        val ruleStart = Regex("""\[/\^""")
        var index = 0
        while (true) {
            val match = ruleStart.find(text, index) ?: break
            val open = match.range.first + 1              // position of the '/'
            val close = findRegexEnd(text, open + 1) ?: break
            val body = text.substring(open + 1, close)
            val entryEnd = text.indexOf("}]", close).let { if (it < 0) text.length else it }
            val entry = text.substring(close, entryEnd)
            val present = languages.filter { Regex("""\b$it\s*:""").containsMatchIn(entry) }.toSet()
            runCatching { Pattern.compile(body) }.getOrNull()?.let { rules.add(Rule(it, present)) }
            index = entryEnd + 1
        }
        return rules
    }

    /** Finds the closing '/' of a JS regex literal, skipping escapes and classes. */
    private fun findRegexEnd(text: String, from: Int): Int? {
        var i = from
        var inClass = false
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '[' -> inClass = true
                ']' -> inClass = false
                '/' -> if (!inClass) return i
                '\n' -> return null
            }
            i++
        }
        return null
    }

    private fun messages(result: JSONObject): List<String> {
        val out = mutableListOf(result.getString("summary"))
        val findings = result.getJSONArray("findings")
        for (i in 0 until findings.length()) {
            val f = findings.getJSONObject(i)
            out.add(f.getString("title"))
            out.add(f.getString("detail"))
        }
        return out
    }

    /** Every fixture the repair tests use, so coverage follows the same branches. */
    private fun allEmittedMessages(): List<String> {
        val out = linkedSetOf<String>()

        fun collect(build: () -> FakeBlockDevice) {
            out.addAll(messages(PartitionRepair.scanDevice(build())))
            out.addAll(messages(PartitionRepair.repairDevice(build(), allowRisky = false)))
            out.addAll(messages(PartitionRepair.repairDevice(build(), allowRisky = true)))
            out.addAll(
                messages(
                    PartitionRepair.repairDevice(build(), allowRisky = true, allowDataLoss = true)
                )
            )
        }

        // The destructive in-place NTFS rebuild, which only a full-size partition
        // is offered, so its wording cannot drift out of the rule table either.
        collect { DriveImages.hopelessNtfsPartition() }

        collect { DriveImages.healthyNtfsDrive() }
        collect { FakeBlockDevice(20_000) }
        collect {
            DriveImages.healthyNtfsDrive().also {
                val broken = DriveImages.ntfsBoot()
                DriveImages.le(broken, 40, 999_999L, 8)
                it.put(DriveImages.PART_START, broken)
            }
        }
        collect {
            DriveImages.healthyNtfsDrive().also {
                it.put(DriveImages.PART_START + DriveImages.PART_SECTORS - 1, ByteArray(512))
            }
        }
        collect {
            DriveImages.healthyNtfsDrive().also { it.put(0, DriveImages.mbr(type = 0x0C)) }
        }
        collect {
            DriveImages.healthyNtfsDrive().also { it.put(0, DriveImages.mbr(active = false)) }
        }
        collect {
            FakeBlockDevice(20_000).also {
                it.put(0, DriveImages.mbr())
                val broken = DriveImages.ntfsBoot(totalSectors = 999_999L)
                it.put(DriveImages.PART_START, broken)
                it.put(DriveImages.PART_START + DriveImages.PART_SECTORS - 1, broken)
                it.put(DriveImages.PART_START + 100 * 8, DriveImages.mftRecord())
            }
        }
        collect {
            FakeBlockDevice(20_000).also {
                it.put(0, DriveImages.mbr())
                val broken = DriveImages.ntfsBoot(totalSectors = 999_999L)
                it.put(DriveImages.PART_START, broken)
                it.put(DriveImages.PART_START + DriveImages.PART_SECTORS - 1, broken)
            }
        }
        collect {
            FakeBlockDevice(20_000).also {
                it.put(DriveImages.PART_START, DriveImages.ntfsBoot())
                it.put(DriveImages.PART_START + DriveImages.PART_SECTORS - 1, DriveImages.ntfsBoot())
                it.put(DriveImages.PART_START + 100 * 8, DriveImages.mftRecord())
            }
        }
        val total = 20_000L
        collect {
            FakeBlockDevice(total).also {
                it.put(0, DriveImages.protectiveMbr(total - 1))
                it.put(total - 1, DriveImages.gptHeader(total - 1, 1, total - 33, total))
            }
        }
        collect {
            FakeBlockDevice(total).also {
                it.put(0, DriveImages.protectiveMbr(total - 1))
                it.put(1, DriveImages.gptHeader(1, total - 1, 2, total))
            }
        }
        collect {
            FakeBlockDevice(total).also {
                it.put(1, DriveImages.gptHeader(1, total - 1, 2, total))
                it.put(total - 1, DriveImages.gptHeader(total - 1, 1, total - 33, total))
            }
        }
        collect {
            FakeBlockDevice(total).also { it.put(0, DriveImages.protectiveMbr(total - 1)) }
        }
        return out.toList()
    }

    @Test
    fun `i18n rules parse and cover every language`() {
        val rules = loadRules()
        assertTrue("no translation rules were parsed from i18n.js", rules.size > 50)
        val incomplete = rules.filter { it.languages.size < languages.size }
        assertTrue("rules missing a language: ${incomplete.size}", incomplete.isEmpty())
    }

    @Test
    fun `every repair message emitted by the engine has a translation rule`() {
        val rules = loadRules()
        val untranslated = allEmittedMessages().filter { message ->
            message.isNotBlank() && rules.none { it.pattern.matcher(message).find() }
        }
        assertTrue(
            "these engine messages have no translation rule in i18n.js:\n" +
                untranslated.joinToString("\n") { " - $it" },
            untranslated.isEmpty()
        )
    }
}
