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
    val categories: List<EmojiCategory> = listOf(
        EmojiCategory("Smileys", smileys.distinct()),
        EmojiCategory("Gestures", gestures),
        EmojiCategory("Hearts", hearts.distinct()),
        EmojiCategory("Objects", objects.distinct()),
        EmojiCategory("Symbols", symbols.distinct())
    )
    val allEmojis: List<String> = categories.flatMap { it.emojis }.distinct()

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
