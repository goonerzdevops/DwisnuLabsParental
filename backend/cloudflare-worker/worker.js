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
      await broadcastCommand(botToken, chatId, dbUrl, "LOCK", env);
    } else if (text === "/unlock_all") {
      await broadcastCommand(botToken, chatId, dbUrl, "UNLOCK", env);
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
      await broadcastCommand(botToken, fromId, dbUrl, "LOCK", env);
    } else if (data === "BROADCAST_UNLOCK") {
      await broadcastCommand(botToken, fromId, dbUrl, "UNLOCK", env);
    } else if (data === "BROADCAST_INTERNET_OFF") {
      await broadcastCommand(botToken, fromId, dbUrl, "INTERNET_OFF", env);
    } else if (data === "BROADCAST_INTERNET_ON") {
      await broadcastCommand(botToken, fromId, dbUrl, "INTERNET_ON", env);
    } else if (data.startsWith("DEV_MENU_")) {
      const devId = data.replace("DEV_MENU_", "");
      await sendDeviceSubMenu(botToken, fromId, dbUrl, devId);
    } else if (data.startsWith("CMD_THEME_")) {
      const devId = data.replace("CMD_THEME_", "");
      await sendThemesSubMenu(botToken, fromId, devId, dbUrl);
    } else if (data.startsWith("CMD_")) {
      // Format: CMD_<ACTION>_<DEVICE_ID>
      const parts = data.split("_");
      const action = parts[1]; // LOCK, UNLOCK, PING, etc.
      const devId = parts.slice(2).join("_");
      await sendDeviceCommand(botToken, fromId, dbUrl, devId, action, env);
    }
  }
}

// Helper Format Nama Tampilan Perangkat (Kakak Redmi 9T & Adik Redmi Note 9 Pro)
function getDeviceDisplayName(devId, dev) {
  if (devId === "device_redmi9t" || dev?.model?.toLowerCase()?.includes("9t") || dev?.model?.toLowerCase()?.includes("m2010j19") || dev?.model?.toLowerCase()?.includes("lime")) {
    return "📱 Kakak (Redmi 9T)";
  }
  if (devId === "device_note9pro" || dev?.model?.toLowerCase()?.includes("note 9 pro") || dev?.model?.toLowerCase()?.includes("joyeuse") || dev?.model?.toLowerCase()?.includes("curtana")) {
    return "📱 Adik (Redmi Note 9 Pro)";
  }
  if (dev?.name && !dev.name.includes("Smartphone 1") && !dev.name.includes("Smartphone 2")) {
    if (dev.name.includes("Note 9 Pro")) return "📱 Adik (Redmi Note 9 Pro)";
    if (dev.name.includes("9T")) return "📱 Kakak (Redmi 9T)";
    return dev.name;
  }
  if (devId === "device_padse") return "📟 Tablet (Redmi Pad SE)";
  if (devId === "device_iqooz10") return "📱 Smartphone 3 (iQOO Z10)";
  return dev?.name || devId;
}

// Sub-Menu Pilihan Tema Gertakan untuk 1 Perangkat
async function sendThemesSubMenu(botToken, chatId, devId, dbUrl) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const dev = devices[devId];
  const devName = getDeviceDisplayName(devId, dev);

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
      { text: "🔋 Baterai 15% & Kepanasan", callback_data: `CMD_SET-THEME-BATTERY_${devId}` }
    ],
    [
      { text: "⬅️ Kembali ke Menu Perangkat", callback_data: `DEV_MENU_${devId}` }
    ]
  ];

  const text = `🎭 <b>Pilih Gaya Gertakan / Kamuflase untuk ${devName}:</b>\nTema ini akan langsung muncul di layar saat terkunci:`;
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
  // Filter hanya key device valid (abaikan root command atau objek lain)
  const deviceKeys = Object.keys(devices).filter(k => k.startsWith("device_") || devices[k]?.model || devices[k]?.name);

  // Urutkan prioritas: Kakak (Redmi 9T), Adik (Redmi Note 9 Pro), Tablet, lalu lainnya
  const priority = ["device_redmi9t", "device_note9pro", "device_padse", "device_iqooz10"];
  deviceKeys.sort((a, b) => {
    const idxA = priority.indexOf(a);
    const idxB = priority.indexOf(b);
    if (idxA !== -1 && idxB !== -1) return idxA - idxB;
    if (idxA !== -1) return -1;
    if (idxB !== -1) return 1;
    return a.localeCompare(b);
  });

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
    const name1 = getDeviceDisplayName(k1, d1);
    row.push({
      text: `${lockIcon1} ${dot1} ${name1} (${d1.battery || 0}%)`,
      callback_data: `DEV_MENU_${k1}`
    });

    if (i + 1 < deviceKeys.length) {
      const k2 = deviceKeys[i + 1];
      const d2 = devices[k2];
      const lockIcon2 = d2.isLocked ? "🔒" : "🔓";
      const st2 = getDeviceOnlineStatus(d2.lastSeen);
      const dot2 = st2.isOnline ? "🟢" : "🔴";
      const name2 = getDeviceDisplayName(k2, d2);
      row.push({
        text: `${lockIcon2} ${dot2} ${name2} (${d2.battery || 0}%)`,
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
async function sendDeviceSubMenu(botToken, chatId, dbUrl, devId, cachedDev = null) {
  let dev = cachedDev;
  if (!dev) {
    const devices = await getDevicesFromFirebase(dbUrl);
    dev = devices[devId] || { name: devId, battery: 0, isLocked: false, isInternetBlocked: false };
  }
  const displayName = getDeviceDisplayName(devId, dev);

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
      { text: "🎭 Ganti Tema Gertakan", callback_data: `CMD_THEME_${devId}` },
      { text: "📡 Bangunkan / Cek Live", callback_data: `CMD_PING_${devId}` }
    ],
    [
      { text: "⬅️ Kembali ke Menu Utama", callback_data: "MENU_MAIN" }
    ]
  ];

  const a11yLine = dev.isAccessibilityActive !== undefined
    ? `🛡️ Aksesibilitas : ${dev.isAccessibilityActive ? "✅ Aktif" : "⚠️ Nonaktif"}\n`
    : "";

  const text = `📱 <b>KENDALI PERANGKAT: ${displayName}</b>\n` +
               `━━━━━━━━━━━━━━━━━━━\n` +
               `📡 Status Koneksi: <b>${onlineInfo.text}</b>\n` +
               `🔋 Baterai       : ${dev.battery || 0}%\n` +
               `🔒 Status Layar  : ${lockStatus}\n` +
               `📶 Status Internet: ${netStatus}\n` +
               a11yLine +
               `📱 Sedang Dibuka : <b>${curApp}</b>\n` +
               `━━━━━━━━━━━━━━━━━━━` +
               offlineNotice +
               `\nPilih tindakan untuk perangkat ini:`;

  await sendTelegramMessage(botToken, chatId, text, { inline_keyboard: keyboard });
}

// Mengirim Perintah ke 1 Perangkat Spesifik via Firebase + FCM High-Priority Wake Push
async function sendDeviceCommand(botToken, chatId, dbUrl, devId, action, env) {
  const payload = {
    action: action,
    timestamp: Date.now()
  };

  try {
    // 1. Ambil data perangkat (termasuk fcmToken) & tulis perintah real-time ke Firebase secara paralel
    const [, devices] = await Promise.all([
      fetch(`${dbUrl}/devices/${devId}/command.json`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      }),
      getDevicesFromFirebase(dbUrl)
    ]);

    const dev = devices[devId] ? { ...devices[devId] } : {};
    const displayName = getDeviceDisplayName(devId, dev);
    const onlineInfo = getDeviceOnlineStatus(dev?.lastSeen);

    // 2. Kirim sinyal FCM HTTP v1 High-Priority untuk membangunkan proses aplikasi (<1 detik)
    const fcmResult = dev?.fcmToken
      ? await sendFcmHighPriorityPush(env, dev.fcmToken, devId, action, payload.timestamp)
      : { sent: false, reason: "no_fcm_token" };

    // Optimistic state: status langsung sinkron di tampilan Telegram
    if (action === "LOCK") {
      dev.isLocked = true;
    } else if (action === "UNLOCK" || action.startsWith("UNLOCK")) {
      dev.isLocked = false;
    } else if (action === "NETOFF" || action === "INTERNET_OFF" || action === "NET30") {
      dev.isInternetBlocked = true;
    } else if (action === "NETON" || action === "INTERNET_ON") {
      dev.isInternetBlocked = false;
    }

    if (onlineInfo.isOnline) {
      await sendTelegramMessage(botToken, chatId, `⚡ Perintah <b>${action}</b> telah dikirim ke <b>${displayName}</b>!`);
    } else if (fcmResult.sent) {
      await sendTelegramMessage(botToken, chatId, `📡 Sinyal <b>FCM High-Priority</b> dikirim untuk membangunkan <b>${displayName}</b> dan mengeksekusi <b>${action}</b>!`);
    } else {
      await sendTelegramMessage(botToken, chatId, `⏳ Perintah <b>${action}</b> telah dicatat di antrean server untuk <b>${displayName}</b>!\n<i>(Perangkat sedang OFFLINE, perintah akan aktif otomatis begitu HP online)</i>`);
    }

    await sendDeviceSubMenu(botToken, chatId, dbUrl, devId, dev);
  } catch (err) {
    await sendTelegramMessage(botToken, chatId, `❌ Gagal mengirim perintah: ${err.message}`);
  }
}

// Mengirim Perintah ke Semua Perangkat Serentak (Broadcast Firebase + FCM High-Priority)
async function broadcastCommand(botToken, chatId, dbUrl, action, env) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const keys = Object.keys(devices).filter(k => k.startsWith("device_") || devices[k]?.model || devices[k]?.name);

  if (keys.length === 0) {
    await sendTelegramMessage(botToken, chatId, "⚠️ Belum ada perangkat terdaftar untuk di-broadcast.");
    return;
  }

  const ts = Date.now();
  await Promise.all(keys.map(async (k) => {
    try {
      const rtdbPromise = fetch(`${dbUrl}/devices/${k}/command.json`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: action, timestamp: ts })
      });
      const fcmPromise = devices[k]?.fcmToken
        ? sendFcmHighPriorityPush(env, devices[k].fcmToken, k, action, ts)
        : Promise.resolve();
      await Promise.all([rtdbPromise, fcmPromise]);
    } catch (_) {}
  }));

  await sendTelegramMessage(botToken, chatId, `📢 <b>Perintah ${action} berhasil disiarkan ke semua (${keys.length}) HP Anak!</b>`);
  await sendMainMenu(botToken, chatId, dbUrl);
}

// Menampilkan Status Semua Perangkat
async function sendAllDevicesStatus(botToken, chatId, dbUrl) {
  const devices = await getDevicesFromFirebase(dbUrl);
  const keys = Object.keys(devices).filter(k => k.startsWith("device_") || devices[k]?.model || devices[k]?.name);

  // Urutkan prioritas: Kakak (Redmi 9T), Adik (Redmi Note 9 Pro), Tablet, lalu lainnya
  const priority = ["device_redmi9t", "device_note9pro", "device_padse", "device_iqooz10"];
  keys.sort((a, b) => {
    const idxA = priority.indexOf(a);
    const idxB = priority.indexOf(b);
    if (idxA !== -1 && idxB !== -1) return idxA - idxB;
    if (idxA !== -1) return -1;
    if (idxB !== -1) return 1;
    return a.localeCompare(b);
  });

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
    const displayName = getDeviceDisplayName(k, d);
    text += `${lk} <b>${displayName}</b> (${st.text})\n` +
            `   🔋 Baterai: ${d.battery || 0}% | ${net}\n` +
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

// ============================================================================
// FCM HTTP v1 High-Priority Push (Web Crypto RS256 OAuth2 - Zero Dependencies)
// ============================================================================
let cachedFcmToken = null;
let cachedFcmExpiryMs = 0;

function resolveServiceAccount(env) {
  if (!env) return null;
  if (env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const parsed = typeof env.FIREBASE_SERVICE_ACCOUNT === "string"
        ? JSON.parse(env.FIREBASE_SERVICE_ACCOUNT)
        : env.FIREBASE_SERVICE_ACCOUNT;
      if (parsed?.client_email && parsed?.private_key) {
        return {
          projectId: parsed.project_id || env.FIREBASE_PROJECT_ID || "childlock-parental",
          clientEmail: parsed.client_email,
          privateKey: parsed.private_key
        };
      }
    } catch (e) {
      console.error("Invalid FIREBASE_SERVICE_ACCOUNT JSON:", e);
    }
  }
  if (env.FIREBASE_CLIENT_EMAIL && env.FIREBASE_PRIVATE_KEY) {
    return {
      projectId: env.FIREBASE_PROJECT_ID || "childlock-parental",
      clientEmail: env.FIREBASE_CLIENT_EMAIL,
      privateKey: env.FIREBASE_PRIVATE_KEY
    };
  }
  return null;
}

function base64UrlEncodeBytes(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeJson(obj) {
  const bytes = new TextEncoder().encode(JSON.stringify(obj));
  return base64UrlEncodeBytes(bytes);
}

async function importPkcs8PrivateKey(pem) {
  const cleanPem = pem
    .replace(/\\n/g, "\n")
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binaryDer = atob(cleanPem);
  const bytes = new Uint8Array(binaryDer.length);
  for (let i = 0; i < binaryDer.length; i++) {
    bytes[i] = binaryDer.charCodeAt(i);
  }
  return crypto.subtle.importKey(
    "pkcs8",
    bytes.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
}

async function getFcmAccessToken(sa) {
  const nowMs = Date.now();
  if (cachedFcmToken && nowMs < cachedFcmExpiryMs - 60000) {
    return cachedFcmToken;
  }

  const nowSec = Math.floor(nowMs / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claimSet = {
    iss: sa.clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: nowSec,
    exp: nowSec + 3600
  };

  const unsignedJwt = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(claimSet)}`;
  const key = await importPkcs8PrivateKey(sa.privateKey);
  const signatureBuffer = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsignedJwt)
  );
  const signedJwt = `${unsignedJwt}.${base64UrlEncodeBytes(new Uint8Array(signatureBuffer))}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${signedJwt}`
  });

  if (!tokenRes.ok) {
    const errText = await tokenRes.text();
    throw new Error(`OAuth2 token error (${tokenRes.status}): ${errText}`);
  }

  const tokenData = await tokenRes.json();
  cachedFcmToken = tokenData.access_token;
  cachedFcmExpiryMs = nowMs + ((tokenData.expires_in || 3600) * 1000);
  return cachedFcmToken;
}

async function sendFcmHighPriorityPush(env, fcmToken, devId, action, timestamp) {
  const sa = resolveServiceAccount(env);
  if (!sa) {
    return { sent: false, reason: "service_account_not_configured" };
  }

  try {
    const accessToken = await getFcmAccessToken(sa);
    const fcmUrl = `https://fcm.googleapis.com/v1/projects/${sa.projectId}/messages:send`;
    const body = {
      message: {
        token: fcmToken,
        data: {
          action: String(action),
          timestamp: String(timestamp),
          deviceId: String(devId)
        },
        android: {
          priority: "HIGH",
          ttl: "86400s",
          direct_boot_ok: true
        }
      }
    };

    const res = await fetch(fcmUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(body)
    });

    if (!res.ok) {
      const errText = await res.text();
      console.error(`FCM push error for ${devId}:`, errText);
      return { sent: false, reason: errText };
    }

    return { sent: true };
  } catch (err) {
    console.error(`FCM exception for ${devId}:`, err);
    return { sent: false, reason: err.message };
  }
}
