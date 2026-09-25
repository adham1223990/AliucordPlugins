package textreact

// huge thanks to https://github.com/Juby210/text-react/blob/master/index.js
// rewritten for TextReact:
//  - fixed shared-mutable-state bug (same sentence failing the 2nd time)
//  - removed the cross (✝) and zodiac/astrology symbols
//  - massively expanded letter/digit variant pools to support repeated
//    characters within the same reaction set
//  - rewritten as a proper left-to-right scan instead of fragile string
//    slicing/replace

object TextToEmoji {

    // ==========================================================
    // Variant pool construction
    // ==========================================================

    private fun charFromCodePoint(cp: Int): String = String(Character.toChars(cp))

    /**
     * يضيف 26 قيمة (حرف a..z) بترتيب متتابع في الـ Unicode بداية من [baseCp] لكل
     * حرف في [pool]. مش كل النطاقات دي مضمون إنها "إيموجي" رسمية عند ديسكورد؛ أي
     * قيمة مش موجودة في خريطة إيموجي ديسكورد هيتم تجاهلها تلقائياً وقت الإرسال من
     * غير ما تعمل كراش أو تتحط كرياكشن فاضي (شوف TextReact.kt).
     */
    private fun addLetterRange(pool: MutableMap<Char, MutableList<String>>, baseCp: Int) {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        for (i in letters.indices) {
            pool.getOrPut(letters[i]) { mutableListOf() }.add(charFromCodePoint(baseCp + i))
        }
    }

    private fun addDigitRange(pool: MutableMap<Char, MutableList<String>>, baseCp: Int, digits: String) {
        for (i in digits.indices) {
            pool.getOrPut(digits[i]) { mutableListOf() }.add(charFromCodePoint(baseCp + i))
        }
    }

    /** القوائم الأصلية المختارة يدوياً (نفس روح النسخة القديمة)، بعد شيل أي صليب أو رمز تنجيم/أبراج. */
    private fun curatedSingle(): MutableMap<Char, MutableList<String>> {
        val m = mutableMapOf<Char, MutableList<String>>()
        fun set(c: Char, vararg values: String) {
            m[c] = values.toMutableList()
        }
        set('a', "\uD83C\uDDE6")
        set('b', "\uD83C\uDDE7")
        set('c', "\uD83C\uDDE8", "©")
        set('d', "\uD83C\uDDE9")
        set('e', "\uD83C\uDDEA", "\uD83D\uDCE7", "🎼")
        set('f', "\uD83C\uDDEB")
        set('g', "\uD83C\uDDEC")
        set('h', "\uD83C\uDDED") // شيلنا ♓ (برج الحوت)
        set('i', "\uD83C\uDDEE", "ℹ")
        set('j', "\uD83C\uDDEF")
        set('k', "\uD83C\uDDF0")
        set('l', "\uD83C\uDDF1")
        set('m', "\uD83C\uDDF2", "Ⓜ") // شيلنا ♏ و ♍ (برجي العقرب والعذراء)
        set('n', "\uD83C\uDDF3") // شيلنا ♑ (برج الجدي)
        set('o', "\uD83C\uDDF4", "\uD83C\uDD7E", "⭕")
        set('p', "\uD83C\uDDF5", "\uD83C\uDD7F")
        set('q', "\uD83C\uDDF6")
        set('r', "\uD83C\uDDF7", "®")
        set('s', "\uD83C\uDDF8")
        set('t', "\uD83C\uDDF9") // شيلنا ✝ (الصليب)
        set('u', "\uD83C\uDDFA")
        set('v', "\uD83C\uDDFB") // شيلنا ♈ (برج الحمل)
        set('w', "\uD83C\uDDFC")
        set('x', "\uD83C\uDDFD", "❎", "❌", "✖")
        set('y', "\uD83C\uDDFE")
        set('z', "\uD83C\uDDFF")
        set(' ', "▪", "◾", "➖", "◼", "⬛", "⚫", "\uD83D\uDDA4", "\uD83D\uDD76")
        set('?', "❔", "❓")
        set('+', "➕")
        set('-', "➖", "⛔", "\uD83D\uDCDB")
        set('!', "❕", "❗")
        set('*', "*️⃣")
        set('$', "\uD83D\uDCB2")
        set('#', "#️⃣")
        return m
    }

    private fun curatedDigits(): MutableMap<Char, MutableList<String>> {
        val m = mutableMapOf<Char, MutableList<String>>()
        for (d in '0'..'9') m[d] = mutableListOf("$d\uFE0F\u20E3") // keycap: 0️⃣..9️⃣
        return m
    }

    /**
     * القاموس النهائي: القيم اليدوية الأصلية (المفضّلة، بتتحط الأول) + أكبر عدد
     * ممكن من الأنماط الإضافية كاحتياطي لدعم تكرار نفس الحرف أكتر من مرة في نفس
     * مجموعة الرياكشنز. أي نمط مش مدعوم من ديسكورد هيتجاهل تلقائياً وقت الإرسال.
     */
    private fun buildBaseSinglePool(): Map<Char, List<String>> {
        val pool = curatedSingle()
        val digitPool = curatedDigits()

        // أحرف: نطاقات يونيكود إضافية (كل نطاق كامل A-Z)
        addLetterRange(pool, 0x1F170) // Negative Squared Latin Capital Letter A-Z (🅰-🆉)
        addLetterRange(pool, 0x1F150) // Negative Circled Latin Capital Letter A-Z (best-effort)
        addLetterRange(pool, 0x24B6)  // Circled Latin Capital Letter A-Z (Ⓐ-Ⓩ)
        addLetterRange(pool, 0x24D0)  // Circled Latin Small Letter a-z (ⓐ-ⓩ)
        addLetterRange(pool, 0x249C)  // Parenthesized Latin Small Letter a-z (⒜-⒵)
        addLetterRange(pool, 0xFF21)  // Fullwidth Latin Capital Letter A-Z
        addLetterRange(pool, 0xFF41)  // Fullwidth Latin Small Letter a-z
        addLetterRange(pool, 0x1D400) // Mathematical Bold Capital A-Z
        addLetterRange(pool, 0x1D41A) // Mathematical Bold Small a-z
        addLetterRange(pool, 0x1D5D4) // Mathematical Sans-Serif Bold Capital A-Z
        addLetterRange(pool, 0x1D5EE) // Mathematical Sans-Serif Bold Small a-z
        addLetterRange(pool, 0x1D670) // Mathematical Monospace Capital A-Z
        addLetterRange(pool, 0x1D68A) // Mathematical Monospace Small a-z

        // أرقام: نطاقات يونيكود إضافية
        addDigitRange(digitPool, 0x2460, "123456789")       // Circled Digit 1-9 (①-⑨)
        digitPool.getOrPut('0') { mutableListOf() }.add(charFromCodePoint(0x24EA)) // ⓪
        addDigitRange(digitPool, 0x2474, "123456789")       // Parenthesized Digit 1-9 (⑴-⑼)
        addDigitRange(digitPool, 0x2776, "123456789")       // Dingbat Negative Circled Digit 1-9 (❶-❾, best-effort)
        addDigitRange(digitPool, 0xFF10, "0123456789")      // Fullwidth Digit 0-9
        addDigitRange(digitPool, 0x1D7CE, "0123456789")     // Mathematical Bold Digit 0-9
        addDigitRange(digitPool, 0x1D7D8, "0123456789")     // Mathematical Double-Struck Digit 0-9
        addDigitRange(digitPool, 0x1D7E2, "0123456789")     // Mathematical Sans-Serif Digit 0-9
        addDigitRange(digitPool, 0x1D7F6, "0123456789")     // Mathematical Monospace Digit 0-9

        val merged = LinkedHashMap<Char, List<String>>()
        for ((k, v) in pool) merged[k] = v
        for ((k, v) in digitPool) merged[k] = (merged[k].orEmpty() + v)
        return merged
    }

    private val BASE_SINGLE: Map<Char, List<String>> = buildBaseSinglePool()

    private val BASE_MULTIPLE: Map<String, List<String>> = mapOf(
        "wc" to listOf("\uD83D\uDEBE"),
        "back" to listOf("\uD83D\uDD19"),
        "end" to listOf("\uD83D\uDD1A"),
        "on!" to listOf("\uD83D\uDD1B"),
        "soon" to listOf("\uD83D\uDD1C"),
        "top" to listOf("\uD83D\uDD1D"),
        "!!" to listOf("‼"),
        "!?" to listOf("⁉"),
        "tm" to listOf("™"),
        "10" to listOf("\uD83D\uDD1F"),
        "cl" to listOf("\uD83C\uDD91"),
        "cool" to listOf("\uD83C\uDD92"),
        "free" to listOf("\uD83C\uDD93"),
        "id" to listOf("\uD83C\uDD94"),
        "new" to listOf("\uD83C\uDD95"),
        "ng" to listOf("\uD83C\uDD96"),
        "ok" to listOf("\uD83C\uDD97"),
        "sos" to listOf("\uD83C\uDD98"),
        "up!" to listOf("\uD83C\uDD99"),
        "vs" to listOf("\uD83C\uDD9A"),
        "abc" to listOf("\uD83D\uDD24"),
        "ab" to listOf("\uD83C\uDD8E"),
        "18" to listOf("\uD83D\uDD1E"),
        "100" to listOf("\uD83D\uDCAF"),
        "atm" to listOf("\uD83C\uDFE7")
    )

    // مرتبة تنازلياً حسب الطول، عشان الكلمات الأطول (زي "cool") تتفحص قبل الأقصر
    // ("cl") لو حصل تداخل في أول الحروف.
    private val MULTI_KEYS_BY_LENGTH_DESC: List<String> = BASE_MULTIPLE.keys.sortedByDescending { it.length }

    // ==========================================================
    // Core algorithm
    // ==========================================================

    /**
     * بيحول نص لقايمة رياكشنز. كل نداء بيستخدم نسخة محلية مستقلة من القوائم (مش
     * الـ companion object المشترك القديم)، فاستخدام نفس الجملة أكتر من مرة بيدي
     * نفس النتيجة دايماً.
     *
     * @return Pair(الرياكشنز الناتجة, incomplete) — incomplete = true لو فيه حرف
     * مش معروف أو المتاح من نفس الحرف خلص (تكرار أكتر من عدد الأنماط المتاحة).
     */
    fun generateEmojiArray(string: String): Pair<List<String>, Boolean> {
        val msg = string.lowercase()
        val n = msg.length

        // نسخ محلية (deep copy) عشان الشيل هنا يأثرش على أي نداء تاني
        val singlePool: MutableMap<Char, MutableList<String>> =
            BASE_SINGLE.mapValuesTo(HashMap()) { (_, v) -> ArrayList(v) }
        val multiplePool: MutableMap<String, MutableList<String>> =
            BASE_MULTIPLE.mapValuesTo(HashMap()) { (_, v) -> ArrayList(v) }

        val usedThisMessage = HashSet<String>()
        val result = ArrayList<String>()
        var incomplete = false

        fun takeUnique(list: MutableList<String>?): String? {
            if (list == null) return null
            val it = list.iterator()
            while (it.hasNext()) {
                val candidate = it.next()
                it.remove()
                if (usedThisMessage.add(candidate)) return candidate
                // القيمة دي مستخدمة بالفعل في نفس الرسالة (تصادم بين نطاقين
                // مختلفين)؛ نكمل ندور على القيمة اللي بعدها في نفس القايمة.
            }
            return null
        }

        var i = 0
        while (i < n) {
            var matched = false

            for (key in MULTI_KEYS_BY_LENGTH_DESC) {
                if (msg.startsWith(key, i)) {
                    val emoji = takeUnique(multiplePool[key])
                    if (emoji != null) result.add(emoji) else incomplete = true
                    i += key.length
                    matched = true
                    break
                }
            }
            if (matched) continue

            val c = msg[i]
            val pool = singlePool[c]
            if (pool != null) {
                val emoji = takeUnique(pool)
                if (emoji != null) result.add(emoji) else incomplete = true // خلصت كل الأنماط لنفس الحرف
            } else {
                incomplete = true // حرف مش معروف خالص
            }
            i++
        }

        return Pair(result, incomplete)
    }
}
