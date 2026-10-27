# Panduan Deploy Cloudflare Worker (1 Menit)

Script `worker.js` di folder ini bertindak sebagai **Otak Serverless Telegram Bot Terpusat** untuk mengendalikan 4 HP anak sekaligus.

---

### Cara Pasang ke Cloudflare (100% Gratis):

1. Login ke [dash.cloudflare.com](https://dash.cloudflare.com).
2. Di sidebar kiri, klik **Workers & Pages** -> **Create application** -> **Create Worker**.
3. Beri nama worker (misal: `dwisnu-parental-bot`), lalu klik **Deploy**.
4. Setelah ter-deploy, klik tombol **"Edit code"**.
5. Hapus semua kode bawaan di editor Cloudflare, lalu **Copy & Paste seluruh isi file [worker.js](file:///c:/D/Project/Mix/Parental/backend/cloudflare-worker/worker.js)** ke editor tersebut.
6. Ganti nilai `FIREBASE_DB_URL_PLACEHOLDER` pada baris ke-12 dengan URL Realtime Database Firebase Anda (misal: `https://childlock-parental-default-rtdb.firebaseio.com`).
7. Klik **Save and deploy** di pojok kanan atas.
8. Salin URL Worker Anda (misal: `https://dwisnu-parental-bot.<username>.workers.dev`).

---

### Cara Menghubungkan Telegram ke Cloudflare Worker (Set Webhook):

Cukup buka browser Anda dan ketik URL berikut (ganti `<URL_WORKER_ANDA>` dengan URL dari Cloudflare):
```
https://api.telegram.org/bot8663165335:AAHqGIqCt_68w4lJIZUGcfJFoFmmNI7BnmU/setWebhook?url=<URL_WORKER_ANDA>
```

Jika sukses, browser akan menampilkan pesan:
```json
{"ok":true,"result":true,"description":"Webhook was set"}
```
Selesai! Kini bot Telegram Anda berjalan 24/7 di serverless Cloudflare tanpa membebani baterai HP sama sekali!
