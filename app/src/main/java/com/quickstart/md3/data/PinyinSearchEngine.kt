package com.quickstart.md3.data

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

object PinyinSearchEngine {

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    private val defaultPolyphones = mapOf(
        "重庆" to "chong qing",
        "银行" to "yin hang",
        "长沙" to "chang sha",
        "成都" to "cheng du",
        "厦门" to "xia men",
        "西藏" to "xi zang",
        "宝藏" to "bao zang",
        "会计" to "kuai ji",
        "重要" to "zhong yao",
        "重生" to "chong sheng",
        "调音" to "tiao yin",
        "空调" to "kong tiao",
        "占卜" to "zhan bu",
        "地壳" to "di qiao",
        "音乐" to "yin yue",
        "支付宝" to "zhi fu bao",
        "快启动" to "kuai qi dong"
    )

    private val whitespaceRegex = Regex("\\s+")

    private val defaultPolyphoneByFirstChar: Map<Char, List<Pair<String, List<String>>>> =
        buildByFirstChar(
            defaultPolyphones.mapValues { (_, v) ->
                v.trim().split(whitespaceRegex).filter { it.isNotEmpty() }
            }
        )

    @Volatile
    private var cachedUserPolyphones: List<PolyphoneEntry>? = null

    @Volatile
    private var cachedPolyphoneByFirstChar: Map<Char, List<Pair<String, List<String>>>> = defaultPolyphoneByFirstChar

    private fun buildByFirstChar(dict: Map<String, List<String>>): Map<Char, List<Pair<String, List<String>>>> {
        val grouped = HashMap<Char, MutableList<Pair<String, List<String>>>>()
        for ((word, syllables) in dict) {
            if (word.isNotEmpty() && syllables.size == word.length) {
                grouped.getOrPut(word[0]) { mutableListOf() }.add(word to syllables)
            }
        }
        grouped.values.forEach { list -> list.sortByDescending { it.first.length } }
        return grouped
    }

    private fun getPolyphoneByFirstChar(userPolyphones: List<PolyphoneEntry>): Map<Char, List<Pair<String, List<String>>>> {
        if (userPolyphones.isEmpty()) return defaultPolyphoneByFirstChar
        if (cachedUserPolyphones == userPolyphones) return cachedPolyphoneByFirstChar
        val combined = LinkedHashMap<String, List<String>>(defaultPolyphones.size + userPolyphones.size)
        defaultPolyphones.forEach { (k, v) ->
            combined[k] = v.trim().split(whitespaceRegex).filter { it.isNotEmpty() }
        }
        userPolyphones.forEach { entry ->
            if (entry.word.isNotBlank() && entry.pinyin.isNotBlank()) {
                combined[entry.word] = entry.pinyin.trim().lowercase().split(whitespaceRegex).filter { it.isNotEmpty() }
            }
        }
        return buildByFirstChar(combined).also {
            cachedPolyphoneByFirstChar = it
            cachedUserPolyphones = userPolyphones
        }
    }

    fun charToT9(c: Char): Char? = when (c.lowercaseChar()) {
        'a', 'b', 'c' -> '2'
        'd', 'e', 'f' -> '3'
        'g', 'h', 'i' -> '4'
        'j', 'k', 'l' -> '5'
        'm', 'n', 'o' -> '6'
        'p', 'q', 'r', 's' -> '7'
        't', 'u', 'v' -> '8'
        'w', 'x', 'y', 'z' -> '9'
        in '0'..'9' -> c
        else -> null
    }

    fun stringToT9(s: String): String = buildString {
        for (ch in s) {
            charToT9(ch)?.let { append(it) }
        }
    }

    data class IndexedTokens(
        val fullPinyin: List<String>,
        val initials: List<String>,
        val t9Full: List<String>,
        val t9Initials: List<String>
    )

    fun buildTokens(text: String, userPolyphones: List<PolyphoneEntry>): IndexedTokens {
        if (text.isBlank()) return IndexedTokens(emptyList(), emptyList(), emptyList(), emptyList())

        val polyByFirstChar = getPolyphoneByFirstChar(userPolyphones)

        // Tokenize text using dictionary longest-match first, then char-by-char
        val segmentsOptions = mutableListOf<List<List<String>>>()
        var idx = 0
        val clean = text.trim()
        while (idx < clean.length) {
            var matchedWord: String? = null
            var matchedSyllables: List<String>? = null
            val candidates = polyByFirstChar[clean[idx]]
            if (candidates != null) {
                for ((word, syllables) in candidates) {
                    if (clean.startsWith(word, idx)) {
                        matchedWord = word
                        matchedSyllables = syllables
                        break
                    }
                }
            }
            if (matchedWord != null && matchedSyllables != null) {
                for (syl in matchedSyllables) {
                    segmentsOptions.add(listOf(listOf(syl)))
                }
                idx += matchedWord.length
            } else {
                val ch = clean[idx]
                when {
                    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' -> {
                        // Group contiguous alphanumeric word
                        val start = idx
                        while (idx < clean.length && (clean[idx] in 'A'..'Z' || clean[idx] in 'a'..'z' || clean[idx] in '0'..'9')) {
                            idx++
                        }
                        val token = clean.substring(start, idx).lowercase()
                        segmentsOptions.add(listOf(listOf(token)))
                    }
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                        val pinyins = try {
                            PinyinHelper.toHanyuPinyinStringArray(ch, format)
                                ?.distinct()
                                ?.take(3)
                                ?: emptyList()
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        if (pinyins.isEmpty()) {
                            segmentsOptions.add(listOf(listOf(ch.lowercase())))
                        } else {
                            segmentsOptions.add(pinyins.map { listOf(it) })
                        }
                        idx++
                    }
                    else -> {
                        idx++
                    }
                }
            }
        }

        // Cartesian product capped at 12 variants to stay ultra-fast
        var variants = listOf(emptyList<String>())
        for (options in segmentsOptions) {
            val next = ArrayList<List<String>>(variants.size * options.size)
            for (prefix in variants) {
                for (opt in options) {
                    next.add(prefix + opt)
                    if (next.size >= 12) break
                }
                if (next.size >= 12) break
            }
            variants = next
        }

        val fullList = variants.map { it.joinToString("") }.distinct()
        val initialsList = variants.map { segs ->
            segs.mapNotNull { it.firstOrNull() }.joinToString("")
        }.distinct()
        val t9Full = fullList.map { stringToT9(it) }.filter { it.isNotEmpty() }.distinct()
        val t9Initials = initialsList.map { stringToT9(it) }.filter { it.isNotEmpty() }.distinct()

        return IndexedTokens(fullList, initialsList, t9Full, t9Initials)
    }

    private val textTokenCache = android.util.LruCache<String, IndexedTokens>(256)

    /**
     * Returns match score >= 0 if matched (smaller = better rank), or -1 if not matched.
     */
    fun scoreAppMatch(app: AppEntry, rawQuery: String, isT9: Boolean, superFuzzy: Boolean): Int {
        return scoreTokenizedMatch(
            label = app.displayName,
            packageName = app.packageName,
            pinyinFullList = app.pinyinFullList,
            pinyinInitialsList = app.pinyinInitialsList,
            t9FullList = app.t9FullList,
            t9InitialsList = app.t9InitialsList,
            rawQuery = rawQuery,
            isT9 = isT9,
            superFuzzy = superFuzzy
        )
    }

    fun scoreTextMatch(
        text: String,
        rawQuery: String,
        isT9: Boolean,
        superFuzzy: Boolean,
        extraText: String = ""
    ): Int {
        if (rawQuery.isBlank()) return 0
        val tokens = textTokenCache.get(text) ?: buildTokens(text, emptyList()).also {
            textTokenCache.put(text, it)
        }
        return scoreTokenizedMatch(
            label = text,
            packageName = extraText,
            pinyinFullList = tokens.fullPinyin,
            pinyinInitialsList = tokens.initials,
            t9FullList = tokens.t9Full,
            t9InitialsList = tokens.t9Initials,
            rawQuery = rawQuery,
            isT9 = isT9,
            superFuzzy = superFuzzy
        )
    }

    private fun scoreTokenizedMatch(
        label: String,
        packageName: String,
        pinyinFullList: List<String>,
        pinyinInitialsList: List<String>,
        t9FullList: List<String>,
        t9InitialsList: List<String>,
        rawQuery: String,
        isT9: Boolean,
        superFuzzy: Boolean
    ): Int {
        if (rawQuery.isBlank()) return 0
        val q = rawQuery.trim().lowercase()
        val nameLower = label.lowercase()

        if (!isT9) {
            if (nameLower.startsWith(q)) return 0
            if (nameLower.contains(q)) return 10 + nameLower.indexOf(q)
            pinyinInitialsList.forEach { init ->
                if (init.startsWith(q)) return 5
                val idx = init.indexOf(q)
                if (idx >= 0) return 20 + idx
            }
            pinyinFullList.forEach { full ->
                if (full.startsWith(q)) return 8
                val idx = full.indexOf(q)
                if (idx >= 0) return 30 + idx
            }
            if (packageName.isNotEmpty() && packageName.lowercase().contains(q)) return 60
            if (superFuzzy) {
                pinyinFullList.forEach { full ->
                    if (isSubsequence(q, full)) return 80
                }
                if (isSubsequence(q, nameLower)) return 85
            }
            return -1
        } else {
            val t9q = stringToT9(q)
            if (t9q.isEmpty()) return -1
            t9InitialsList.forEach { init ->
                if (init.startsWith(t9q)) return 0
                val idx = init.indexOf(t9q)
                if (idx >= 0) return 15 + idx
            }
            t9FullList.forEach { full ->
                if (full.startsWith(t9q)) return 5
                val idx = full.indexOf(t9q)
                if (idx >= 0) return 25 + idx
            }
            if (superFuzzy) {
                t9FullList.forEach { full ->
                    if (isSubsequence(t9q, full)) return 75
                }
            }
            return -1
        }
    }

    fun scoreContactMatch(contact: ContactEntry, rawQuery: String, isT9: Boolean, superFuzzy: Boolean): Int {
        if (rawQuery.isBlank()) return 0
        val q = rawQuery.trim().lowercase()
        val digitsOnly = q.filter { it.isDigit() }
        if (digitsOnly.isNotEmpty() && contact.phone.filter { it.isDigit() }.contains(digitsOnly)) {
            return 2
        }
        val nameLower = contact.name.lowercase()
        if (!isT9) {
            if (nameLower.startsWith(q)) return 0
            if (nameLower.contains(q)) return 10
            if (contact.pinyinInitialsList.any { it.contains(q) }) return 15
            if (contact.pinyinFullList.any { it.contains(q) }) return 25
            if (superFuzzy && contact.pinyinFullList.any { isSubsequence(q, it) }) return 70
            return -1
        } else {
            val t9q = stringToT9(q)
            if (t9q.isEmpty()) return -1
            if (contact.t9InitialsList.any { it.startsWith(t9q) }) return 0
            if (contact.t9InitialsList.any { it.contains(t9q) }) return 12
            if (contact.t9FullList.any { it.startsWith(t9q) }) return 16
            if (contact.t9FullList.any { it.contains(t9q) }) return 24
            if (superFuzzy && contact.t9FullList.any { isSubsequence(t9q, it) }) return 70
            return -1
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return true
        var i = 0
        var j = 0
        while (i < needle.length && j < haystack.length) {
            if (needle[i] == haystack[j]) i++
            j++
        }
        return i == needle.length
    }
}
