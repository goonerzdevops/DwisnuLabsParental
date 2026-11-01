# Panduan Deploy Cloudflare Worker

Script `worker.js` di folder ini bertindak sebagai **Otak Serverless Telegram Bot Terpusat** untuk mengendalikan 4 HP anak sekaligus.

---

### 🚀 Cara 1: Deploy Langsung via CLI Terminal (Paling Cepat)

1. Masuk ke folder worker di terminal:
   ```bash
   cd backend/cloudflare-worker
   ```
2. Login sekali saja ke akun Cloudflare Anda (otomatis membuka browser untuk klik "Allow"):
   ```bash
   npx wrangler login
   ```
3. Deploy seketika tanpa buka web:
   ```bash
   npx wrangler deploy
   ```
   *Setiap kali ada perubahan kode di `worker.js`, cukup jalankan `npx wrangler deploy` lagi.*

---

### 🌐 Cara 2: Deploy Manual via Dashboard Web:

1. Login ke [dash.cloudflare.com](https://dash.cloudflare.com).
2. Di sidebar kiri, klik **Workers & Pages** -> **Create application** -> **Create Worker**.
3. Beri nama worker (misal: `dwisnu-parental-bot`), lalu klik **Deploy**.
4. Setelah ter-deploy, klik tombol **"Edit code"**.
5. Hapus semua kode bawaan di editor Cloudflare, lalu **Copy & Paste seluruh isi file [worker.js](file:///c:/D/Project/Mix/Parental/backend/cloudflare-worker/worker.js)** ke editor tersebut.
6. Ganti nilai `FIREBASE_DB_URL_PLACEHOLDER` pada baris ke-12 dengan URL Realtime Database Firebase Anda (misal: `https://childlock-parental-default-rtdb.firebaseio.com`).
7. Klik **Save and deploy** di pojok kanan atas.
8. Salin URL Worker Anda (misal: `https://dwisnu-parental-bot.<username>.workers.dev`).

---

### Cara Menghubungkan Telegram ke Cloudflare Worker (Set Webhook via Terminal):

Jalankan perintah ini di terminal (cukup sekali saja):
```bash
curl -F "url=https://<NAMA_WORKER>.<SUBDOMAIN>.workers.dev" https://api.telegram.org/bot<TOKEN_BOT_TELEGRAM>/setWebhook
```

Atau buka di browser:
```text
https://api.telegram.org/bot<TOKEN_BOT_TELEGRAM>/setWebhook?url=https://<NAMA_WORKER>.<SUBDOMAIN>.workers.dev
```

Jika sukses, browser akan menampilkan pesan:
```json
{"ok":true,"result":true,"description":"Webhook was set"}
```
Selesai! Kini bot Telegram Anda berjalan 24/7 di serverless Cloudflare tanpa membebani baterai HP sama sekali!
