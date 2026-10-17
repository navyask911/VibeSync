package com.example.util

/**
 * Unicode emoji options used by the chat/story pickers and accepted by encrypted reaction events.
 * The AndroidX Emoji2 dependency makes emoji presentation consistent on older system images.
 */
data class EmojiCategory(val title: String, val emojis: List<String>)

object EmojiCatalog {
    private fun entries(value: String): List<String> = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)

    private val smileys = entries("""
😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 🙂 🙃 🫠 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 🫡 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🫣 🤭 🫢 🫡 🤫 🤥 😶 😶‍🌫️ 😐 😑 😬 🫨 🫥 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 😵‍💫 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 💀 ☠️ 💩 🤡 👻 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾
""")
    private val gestureBases = entries("""
👋 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🫶 🫱 🫲 🫳 🫴 🫷 🫸
""")
    private val skinTones = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    private val gestures = (gestureBases + gestureBases.filterNot { it in setOf("🤝", "🫶", "🫱", "🫲", "🫳", "🫴", "🫷", "🫸") }
        .flatMap { base -> skinTones.map { tone -> base + tone } }).distinct()
    private val hearts = entries("""
❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 🫀 🫶 💌
""")
    private val objects = entries("""
💋 💍 💎 🎁 🎈 🎉 🎊 🪅 🪩 🎀 🎗️ 🏆 🥇 🥈 🥉 🏅 🎖️ 🎟️ 🎫 🎭 🎨 🧵 🪡 🧶 🪢 👓 🕶️ 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩱 🩲 🩳 👙 👚 🪭 👛 👜 👝 🛍️ 🎒 🩴 👞 👟 🥾 🥿 👠 👡 🩰 👢 👑 👒 🎩 🎓 🧢 🪖 ⛑️ 📿 💄 💅 🔑 🗝️ 🔒 🔓 🔏 🔐 🔑 🪪 🛠️ ⚒️ 🔨 🪓 ⛏️ ⚙️ 🗜️ ⚖️ 🦯 🔩 ⚗️ 🧪 🧫 🧬 🔬 🔭 📡 💉 🩸 💊 🩹 🩺 🩻 🚪 🛏️ 🛋️ 🪑 🚽 🚿 🛁 🪥 🧴 🧷 🧹 🧺 🧻 🪣 🧼 🫧 🪒 🧽 🪠 🧯 🛒 🚬 ⚰️ 🪦 ⚱️ 🗿 🪧 🪬 📱 📲 ☎️ 📞 📟 📠 🔋 🪫 🔌 💻 🖥️ 🖨️ ⌨️ 🖱️ 🖲️ 💽 💾 💿 📀 🧮 🎥 🎞️ 📽️ 🎬 📺 📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔍 🔎 🕯️ 💡 🔦 🏮 🪔 📔 📕 📖 📗 📘 📙 📚 📓 📒 📃 📜 📄 📰 🗞️ 📑 🔖 🏷️ 💰 🪙 💴 💵 💶 💷 💸 💳 🧾 ✉️ 📧 📨 📩 📤 📥 📦 📫 📪 📬 📭 📮 🗳️ ✏️ ✒️ 🖋️ 🖊️ 🖌️ 🖍️ 📝 💼 📁 📂 🗂️ 📅 📆 🗒️ 🗓️ 📇 📈 📉 📊 📋 📌 📍 📎 🖇️ 📏 📐 ✂️ 🗃️ 🗄️ 🗑️
""")
    private val symbols = entries("""
💯 🔢 #️⃣ *️⃣ 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔠 🔡 🔢 🔣 🔤 🅰️ 🆎 🅱️ 🆑 🆒 🆓 ℹ️ 🆔 Ⓜ️ 🆕 🆖 🅾️ 🆗 🅿️ 🆘 🆙 🆚 🈁 🈂️ 🈷️ 🈶 🈯 🉐 🈹 🈚 🈲 🉑 🈸 🈴 🈳 ㊗️ ㊙️ 🈺 🈵 🔴 🟠 🟡 🟢 🔵 🟣 🟤 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 🟫 ⬛ ⬜ ◼️ ◻️ ◾ ◽ ▪️ ▫️ 🔶 🔷 🔸 🔹 🔺 🔻 💠 🔘 🔳 🔲 ❤️‍🔥 🔆 🔅 ☢️ ☣️ ⚠️ 🚸 ⛔ 🚫 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🔕 🔔 🔈 🔉 🔊 📣 📢 💬 🗯️ 💭 💤 🌀 ♠️ ♥️ ♦️ ♣️ 🃏 🎴 🀄 🔇 🔃 🔄 🔙 🔚 🔛 🔜 🔝 ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ ↩️ ↪️ ⤴️ ⤵️ 🔀 🔁 🔂 ▶️ ⏩ ⏭️ ⏯️ ◀️ ⏪ ⏮️ 🔼 ⏫ 🔽 ⏬ ⏸️ ⏹️ ⏺️ ⏏️ 🎦 🔅 🔆 📶 🛜 📳 📴 ✖️ ➕ ➖ ➗ 🟰 ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ 💱 💲 ⚕️ ♻️ ✅ ☑️ ✔️ ❌ ❎ ➰ ➿ 〽️ ✳️ ✴️ ❇️ ©️ ®️ ™️ 🔰 🔱 ⚜️ 🔟 🕉️ ✡️ ☸️ ☯️ ✝️ ☦️ ☪️ ☮️ 🕎 🔯 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎
""")
    private val animals = entries("""
🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🦍 🦧 🐔 🐧 🐦 🐤 🐣 🐥 🪿 🦆 🦅 🦉 🦇 🪽 🐺 🐗 🐴 🦄 🫎 🫏 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🦠 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🪼 🪸 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦣 🦛 🦏 🐘 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🦮 🐕‍🦺 🐩 🐈 🐈‍⬛ 🪶 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🪺 🪹 🍄 🪷 🌾 💐 🌷 🪻 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 ☔ ☂️ 🌊
""")
    private val food = entries("""
🍏 🍎 🍐 🍊 🍋 🍋‍🟩 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🫒 🥑 🍆 🥔 🥕 🌽 🌶️ 🫑 🥒 🥬 🥦 🧄 🧅 🥜 🫘 🌰 🫚 🫛 🍞 🥐 🥖 🫓 🥨 🥯 🥞 🧇 🧀 🍖 🍗 🥩 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🫔 🥙 🧆 🥚 🍳 🥘 🍲 🫕 🥣 🥗 🍿 🧈 🧂 🥫 🍱 🍘 🍙 🍚 🍛 🍜 🍝 🍠 🍢 🍣 🍤 🍥 🥮 🍡 🥟 🥠 🥡 🦪 🍦 🍧 🍨 🍩 🍪 🎂 🍰 🧁 🥧 🍫 🍬 🍭 🍮 🍯 🍼 🥛 ☕ 🫖 🍵 🍶 🍾 🍷 🍸 🍹 🍺 🍻 🥂 🥃 🫗 🥤 🧋 🧃 🧉 🧊
""")
    private val activities = entries("""
⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🪂 💃 🕺 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🧗 🏄 🏊 🤽 🚣 🧜 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🪈 🎲 ♟️ 🎯 🎳 🎮 🕹️ 🧩 🧸 🪅 🪩 🎰
""")
    private val travel = entries("""
🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🛵 🏍️ 🛺 🚲 🛴 🛹 🛼 🚏 🛣️ 🛤️ 🛢️ ⛽ 🛞 🚨 🚥 🚦 🛑 🚧 ⚓ 🛟 ⛵ 🛶 🚤 🛳️ ⛴️ 🛥️ 🚢 ✈️ 🛩️ 🛫 🛬 🪂 💺 🚁 🚟 🚠 🚡 🛰️ 🚀 🛸 🧳 🌍 🌎 🌏 🌐 🗺️ 🧭 🏔️ ⛰️ 🌋 🗻 🏕️ 🏖️ 🏜️ 🏝️ 🏞️ 🏟️ 🏛️ 🏗️ 🧱 🪨 🛖 🏘️ 🏚️ 🏠 🏡 🏢 🏣 🏤 🏥 🏦 🏨 🏩 🏪 🏫 🏬 🏭 🏯 🏰 💒 🗼 🗽 ⛪ 🕌 🛕 🕍 ⛩️ 🕋 ⛲ ⛺ 🌁 🌃 🏙️ 🌄 🌅 🌆 🌇 🌉 🌌 🎠 🎡 🎢
""")
    private val countryFlagCodes = entries("""
AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CP CR CU CV CW CX CY CZ DE DG DJ DK DM DO DZ EA EC EE EG EH ER ES ET EU FI FJ FK FM FO FR GA GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU IC ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TA TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW
""")
    private val flags = (
        entries("🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️") +
            countryFlagCodes.map { code ->
                buildString {
                    code.forEach { letter ->
                        val codePoint = 0x1F1E6 + (letter.code - 'A'.code)
                        val pair = Character.toChars(codePoint)
                        append(pair[0])
                        append(pair[1])
                    }
                }
            }
        ).distinct()

    val categories: List<EmojiCategory> = listOf(
        EmojiCategory("Smileys & People", smileys.distinct()),
        EmojiCategory("Animals & Nature", animals.distinct()),
        EmojiCategory("Food & Drink", food.distinct()),
        EmojiCategory("Gestures", gestures),
        EmojiCategory("Hearts", hearts.distinct()),
        EmojiCategory("Sports & Activities", activities.distinct()),
        EmojiCategory("Travel & Places", travel.distinct()),
        EmojiCategory("Objects", objects.distinct()),
        EmojiCategory("Symbols", symbols.distinct()),
        EmojiCategory("Flags", flags)
    )
    val allEmojis: List<String> = categories.flatMap { it.emojis }.distinct()

    /** Filter the categorized emoji catalog by a category label or literal emoji sequence. */
    fun search(query: String): List<String> {
        val term = query.trim()
        if (term.isBlank()) return allEmojis
        val matchedCategories = categories.filter { it.title.contains(term, ignoreCase = true) }
        if (matchedCategories.isNotEmpty()) {
            return matchedCategories.flatMap { it.emojis }.distinct()
        }
        return allEmojis.filter { it.contains(term) }
    }

    /** Accept one emoji grapheme (including ZWJ, variation selectors, skin tones and keycaps). */
    fun isEmojiSequence(value: String): Boolean {
        if (value.isBlank() || value.codePointCount(0, value.length) > 16) return false
        val points = value.codePoints().toArray()
        fun emojiBase(cp: Int): Boolean =
            cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
                cp in 0x1F1E6..0x1F1FF
        val hasEmoji = points.any(::emojiBase)
        val keycap = points.contains(0x20E3)
        return hasEmoji && points.all { cp ->
            emojiBase(cp) || cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E ||
                cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 ||
                (keycap && (cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A))
        }
    }
}
