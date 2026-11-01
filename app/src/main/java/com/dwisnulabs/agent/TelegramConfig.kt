package com.dwisnulabs.agent

object TelegramConfig {
    /**
     * Token Bot dari @BotFather (contoh: "1234567890:ABCdefGHIjklMNOpqrSTUvwxyz")
     */
    var BOT_TOKEN: String = "YOUR_BOT_TOKEN_HERE"

    /**
     * ID Telegram Anda sebagai Orang Tua.
     */
    var PARENT_CHAT_ID: Long = 0L

    fun isConfigured(): Boolean {
        return BOT_TOKEN.isNotBlank() && BOT_TOKEN != "YOUR_BOT_TOKEN_HERE" && PARENT_CHAT_ID != 0L
    }
}
