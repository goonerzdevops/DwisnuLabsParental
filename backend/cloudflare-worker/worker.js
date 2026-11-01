/**
 * ChildLockAgent - Cloudflare Worker Telegram Bot & Firebase Relay
 * 100% Free Serverless Webhook for Multi-Device Parental Control
 */

// Kredensial & Konfigurasi Default (Direkomendasikan diisi via Cloudflare Secret CLI: npx wrangler secret put)
const CONFIG = {
  TELEGRAM_BOT_TOKEN: "YOUR_TELEGRAM_BOT_TOKEN_HERE",
  PARENT_CHAT_ID: 0,
  // URL Firebase Realtime Database Anda
  FIREBASE_DB_URL: "https://YOUR_PROJECT_ID-default-rtdb.asia-southeast1.firebasedatabase.app"
};

export default {
  async fetch(request, env, ctx) {
    if (request.method !== "POST") {
      return new Response("ChildLock Relay Worker is Active!", { status: 200 });
    }

    try {
      const update = await request.json();
      await handleTelegramUpdate(update, env);
      return new Response("OK", { status: 200 });
    } catch (err) {
      console.error("Error processing update:", err);
      return new Response("Error: " + err.message + "\n" + (err.stack || ""), { status: 500 });
    }
  }
};

async function handleTelegramUpdate(update, env) {
  const botToken = env?.TELEGRAM_BOT_TOKEN || CONFIG.TELEGRAM_BOT_TOKEN;
  const parentChatId = parseInt(env?.PARENT_CHAT_ID || CONFIG.PARENT_CHAT_ID);
  const dbUrl = (env?.FIREBASE_DB_URL || CONFIG.FIREBASE_DB_URL).replace(/\/$/, "");

  // 1. Tangani Pesan Teks
  if (update.message) {
    const msg = update.message;
    const chatId = msg.chat.id;

    if (chatId !== parentChatId) {
      console.warn("Unauthorized access from Chat ID:", chatId);
      return;
    }

    const text = (msg.text || "").trim().toLowerCase();

    if (text === "/start" || text === "/menu") {
      await sendMainMenu(botToken, chatId, dbUrl);
    } else if (text === "/status") {
      await sendAllDevicesStatus(botToken, chatId, dbUrl);
    } else if (text === "/lock_all") {
      await broadcastCommand(botToken, chatId, dbUrl, "LOCK");
    } else if (text === "/unlock_all") {
      await broadcastCommand(botToken, chatId, dbUrl, "UNLOCK");
    } else {
      await sendTelegramMessage(botToken, chatId, "❓ Perintah tidak dikenali. Ketik /menu untuk membuka pusat kendali.");
    }
  }

  // 2. Tangani Klik Tombol Inline (Callback Query)
  if (update.callback_query) {
    const cb = update.callback_query;
    const fromId = cb.from.id;
    const data = cb.data || "";

    if (fromId !== parentChatId) return;

    await answerCallbackQuery(botToken, cb.id);

    if (data === "MENU_MAIN") {
      await sendMainMenu(botToken, fromId, dbUrl);
    } else if (data === "STATUS_ALL") {
      await sendAllDevicesStatus(botToken, fromId, dbUrl);
    } else if (data === "BROADCAST_LOCK") {
      await broadcastCommand(botToken, fromId, dbUrl, "LOCK");
    } else if (data === "BROADCAST_UNLOCK") {
      await broadcastCommand(botToken, fromId, dbUrl, "UNLOCK");
    } else if (data === "BROADCAST_INTERNET_OFF") {
      await broadcastCommand(botToken, fromId, dbUrl, "INTERNET_OFF");
    } else if (data === "BROADCAST_INTERNET_ON") {
      await broadcastCommand(botToken, fromId, dbUrl, "INTERNET_ON");
    } else if (data.startsWith("DEV_MENU_")) {
      const devId = data.replace("DEV_MENU_", "");
      await sendDeviceSubMenu(botToken, fromId, dbUrl, devId);
    } else if (data.startsWith("CMD_THEME_")) {
      const devId = data.replace("CMD_THEME_", "");
      await sendThemesSubMenu(botToken, fromId, devId);
    } else if (data.startsWith("CMD_")) {
      // Format: CMD_<ACTION>_<DEVICE_ID>
      const parts = data.split("_");
      const action = parts[1]; // LOCK, UNLOCK, etc.
      const devId = parts.slice(2).join("_");
      await sendDeviceCommand(botToken, fromId, dbUrl, devId, action);
    }
  }
}

// Sub-Menu Pilihan Tema Gertakan untuk 1 Perangkat
async function sendThemesSubMenu(botToken, chatId, devId) {
  const keyboard = [
    [
      { text: "⚠️ Sistem Rusak / Error", callback_data: `CMD_SET-THEME-ERROR_${devId}` },
      { text: "🔥 CPU Overheat 88°C", callback_data: `CMD_SET-THEME-OVERHEAT_${devId}` }
    ],
    [
      { text: "🛡️ Virus / Hack Alert", callback_data: `CMD_SET-THEME-THREAT_${devId}` },
      { text: "💥 Fake Crash App", callback_data: `CMD_SET-THEME-CRASH_${devId}` }
    ],
    [
      { text: "⬅️ Kembali ke Menu Perangkat", callback_data: `DEV_MENU_${devId}` }
    ]
  ];

  const text = `🎭 <b>Pilih Gaya Gertakan / Kamuflase untuk ${devId}:</b>\nTema ini akan langsung muncul di layar saat terkunci:`;
  await sendTelegramMessage(botToken, chatId, text, { inline_keyboard: keyboard });
}

// Mengambil seluruh data perangkat dari Firebase Realtime DB
async function getDevicesFromFirebase(dbUrl) {
  try {
    const res = await fetch(`${dbUrl}/devices.json`);
    if (!res.ok) return {};
    return (await res.json()) || {};
  } catch (e) {
    console.error("Firebase fetch error:", e);
    return {};
  }
}

// Helper Cek Status Online / Offline Perangkat
function getDeviceOnlineStatus(lastSeen) {
  if (!lastSeen) return { isOnline: false, text: "🔴 Offline (Belum pernah sinkron)" };
  const diffMs = Math.abs(Date.now() - lastSeen);
  if (diffMs < 5 * 60 * 1000) { // Toleransi hingga 5 menit
    return { isOnline: true, text: "🟢 Online" };
  }
  const mins = Math.max(1, Math.floor(diffMs / 60000));
  if (mins < 60) {
    return { isOnline: false, text: `🔴 Offline (${mins}m lalu)` };
  }
  const hours = Math.floor(mins / 60);
  return { isOnline: false, text: `🔴 Offline (${hours}j lalu)` };
}

// Mengirim Menu Utama dengan daftar semua perangkat
async function sendMainMenu(botToken, chatId, dbUrl) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const deviceKeys = Object.keys(devices);

  let keyboard = [];

  if (deviceKeys.length === 0) {
    keyboard.push([{ text: "🔄 Refresh Daftar HP", callback_data: "MENU_MAIN" }]);
    const text = "🎮 <b>Pusat Kendali HP Anak (ChildLock Central)</b>\n\n<i>Belum ada perangkat yang terhubung. Begitu aplikasi di HP anak dibuka, perangkat akan otomatis muncul di sini!</i>";
    await sendTelegramMessage(botToken, chatId, text, { inline_keyboard: keyboard });
    return;
  }

  // Baris tombol masing-masing perangkat
  for (let i = 0; i < deviceKeys.length; i += 2) {
    const row = [];
    const k1 = deviceKeys[i];
    const d1 = devices[k1];
    const lockIcon1 = d1.isLocked ? "🔒" : "🔓";
    const st1 = getDeviceOnlineStatus(d1.lastSeen);
    const dot1 = st1.isOnline ? "🟢" : "🔴";
    row.push({
      text: `${lockIcon1} ${dot1} ${d1.name || k1} (${d1.battery || 0}%)`,
      callback_data: `DEV_MENU_${k1}`
    });

    if (i + 1 < deviceKeys.length) {
      const k2 = deviceKeys[i + 1];
      const d2 = devices[k2];
      const lockIcon2 = d2.isLocked ? "🔒" : "🔓";
      const st2 = getDeviceOnlineStatus(d2.lastSeen);
      const dot2 = st2.isOnline ? "🟢" : "🔴";
      row.push({
        text: `${lockIcon2} ${dot2} ${d2.name || k2} (${d2.battery || 0}%)`,
        callback_data: `DEV_MENU_${k2}`
      });
    }
    keyboard.push(row);
  }

  // Tombol aksi serentak (Broadcast)
  keyboard.push([
    { text: "🚨 Kunci Semua HP", callback_data: "BROADCAST_LOCK" },
    { text: "🔓 Buka Semua HP", callback_data: "BROADCAST_UNLOCK" }
  ]);
  keyboard.push([
    { text: "📶 Putus Internet Semua", callback_data: "BROADCAST_INTERNET_OFF" },
    { text: "📶 Sambung Internet Semua", callback_data: "BROADCAST_INTERNET_ON" }
  ]);
  keyboard.push([
    { text: "📊 Status Lengkap Semua HP", callback_data: "STATUS_ALL" }
  ]);

  const text = `🎮 <b>Pusat Kendali HP Anak (${deviceKeys.length} Perangkat)</b>\nPilih perangkat anak yang ingin dikontrol di bawah ini:`;
  await sendTelegramMessage(botToken, chatId, text, { inline_keyboard: keyboard });
}

// Menu Kendali Khusus 1 Perangkat
async function sendDeviceSubMenu(botToken, chatId, dbUrl, devId) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const dev = devices[devId] || { name: devId, battery: 0, isLocked: false, isInternetBlocked: false };

  const onlineInfo = getDeviceOnlineStatus(dev.lastSeen);
  const lockStatus = dev.isLocked ? "🔒 Terkunci" : "🔓 Terbuka";
  const netStatus = dev.isInternetBlocked ? "❌ Diputus (Buffering)" : "✅ Normal";
  const curApp = dev.currentApp || "Layar Utama";

  const offlineNotice = !onlineInfo.isOnline
    ? "\n\n⚠️ <i>Catatan: HP sedang offline (terputus/layanan mati). Perintah tetap disimpan di server dan akan dieksekusi begitu HP kembali aktif.</i>"
    : "";

  const keyboard = [
    [
      { text: "🔒 Kunci Sekarang", callback_data: `CMD_LOCK_${devId}` },
      { text: "🔓 Buka Kunci", callback_data: `CMD_UNLOCK_${devId}` }
    ],
    [
      { text: "⏳ Buka 15 Menit", callback_data: `CMD_UNLOCK15_${devId}` },
      { text: "⏳ Buka 30 Menit", callback_data: `CMD_UNLOCK30_${devId}` }
    ],
    [
      { text: dev.isInternetBlocked ? "📶 Sambung Internet" : "📶 Putus Internet", callback_data: dev.isInternetBlocked ? `CMD_NETON_${devId}` : `CMD_NETOFF_${devId}` },
      { text: "⏳ Putus 30 Menit", callback_data: `CMD_NET30_${devId}` }
    ],
    [
      { text: "🎭 Ganti Tema Gertakan", callback_data: `CMD_THEME_${devId}` }
    ],
    [
      { text: "⬅️ Kembali ke Menu Utama", callback_data: "MENU_MAIN" }
    ]
  ];

  const text = `📱 <b>KENDALI PERANGKAT: ${dev.name}</b>\n` +
               `━━━━━━━━━━━━━━━━━━━\n` +
               `📡 Status Koneksi: <b>${onlineInfo.text}</b>\n` +
               `🔋 Baterai       : ${dev.battery}%\n` +
               `🔒 Status Layar  : ${lockStatus}\n` +
               `📶 Status Internet: ${netStatus}\n` +
               `📱 Sedang Dibuka : <b>${curApp}</b>\n` +
               `━━━━━━━━━━━━━━━━━━━` +
               offlineNotice +
               `\nPilih tindakan untuk perangkat ini:`;

  await sendTelegramMessage(botToken, chatId, text, { inline_keyboard: keyboard });
}

// Mengirim Perintah ke 1 Perangkat Spesifik via Firebase
async function sendDeviceCommand(botToken, chatId, dbUrl, devId, action) {
  const payload = {
    action: action,
    timestamp: Date.now()
  };

  try {
    await fetch(`${dbUrl}/devices/${devId}/command.json`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    // Cek status apakah online
    const devices = await getDevicesFromFirebase(dbUrl);
    const dev = devices[devId];
    const onlineInfo = getDeviceOnlineStatus(dev?.lastSeen);

    if (onlineInfo.isOnline) {
      await sendTelegramMessage(botToken, chatId, `⚡ Perintah <b>${action}</b> telah dikirim ke perangkat <b>${devId}</b>!`);
    } else {
      await sendTelegramMessage(botToken, chatId, `⏳ Perintah <b>${action}</b> telah dicatat di antrean server untuk <b>${devId}</b>!\n<i>(Perangkat sedang OFFLINE, perintah akan aktif otomatis begitu HP online)</i>`);
    }

    await sendDeviceSubMenu(botToken, chatId, dbUrl, devId);
  } catch (err) {
    await sendTelegramMessage(botToken, chatId, `❌ Gagal mengirim perintah: ${err.message}`);
  }
}

// Mengirim Perintah ke Semua Perangkat Serentak (Broadcast)
async function broadcastCommand(botToken, chatId, dbUrl, action) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const keys = Object.keys(devices);

  if (keys.length === 0) {
    await sendTelegramMessage(botToken, chatId, "⚠️ Belum ada perangkat terdaftar untuk di-broadcast.");
    return;
  }

  for (const k of keys) {
    try {
      await fetch(`${dbUrl}/devices/${k}/command.json`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: action, timestamp: Date.now() })
      });
    } catch (_) {}
  }

  await sendTelegramMessage(botToken, chatId, `📢 <b>Perintah ${action} berhasil disiarkan ke semua (${keys.length}) HP Anak!</b>`);
  await sendMainMenu(botToken, chatId, dbUrl);
}

// Menampilkan Status Semua Perangkat
async function sendAllDevicesStatus(botToken, chatId, dbUrl) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const keys = Object.keys(devices);

  if (keys.length === 0) {
    await sendTelegramMessage(botToken, chatId, "Belum ada perangkat terdaftar.");
    return;
  }

  let text = "📊 <b>STATUS SEMUA PERANGKAT ANAK</b>\n━━━━━━━━━━━━━━━━━━━\n";
  for (const k of keys) {
    const d = devices[k];
    const lk = d.isLocked ? "🔒" : "🔓";
    const net = d.isInternetBlocked ? "❌ Net Off" : "✅ Net On";
    const st = getDeviceOnlineStatus(d.lastSeen);
    text += `${lk} <b>${d.name || k}</b> (${st.text})\n` +
            `   🔋 Baterai: ${d.battery}% | ${net}\n` +
            `   📱 Sedang buka: ${d.currentApp || "Layar Utama"}\n\n`;
  }
  text += "━━━━━━━━━━━━━━━━━━━";

  const keyboard = [[{ text: "⬅️ Menu Utama", callback_data: "MENU_MAIN" }]];
  await sendTelegramMessage(botToken, chatId, text, { inline_keyboard: keyboard });
}

// Helper Kirim Pesan ke Telegram
async function sendTelegramMessage(botToken, chatId, text, replyMarkup = null) {
  const url = `https://api.telegram.org/bot${botToken}/sendMessage`;
  const body = {
    chat_id: chatId,
    text: text,
    parse_mode: "HTML"
  };
  if (replyMarkup) body.reply_markup = replyMarkup;

  await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

// Helper Jawab Callback Query
async function answerCallbackQuery(botToken, queryId) {
  const url = `https://api.telegram.org/bot${botToken}/answerCallbackQuery`;
  await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ callback_query_id: queryId })
  });
}
