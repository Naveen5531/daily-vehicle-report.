# Daily Vehicle Report – Android app

Reads WhatsApp group messages, matches **dedicated vehicles** from the last 4 digits drivers send,
and exports Excel reports. No login, no internet; all data stays on the phone.

## Tabs
| Tab | What it shows |
|---|---|
| **Today** | Progress ring (X of Y reported), Reported / Pending / Check counts, vehicles in time-wise order (who reported first) or status-wise, and a call button for pending drivers |
| **WhatsApp** | Pick one group to read (or all chats), every captured message and how it was matched, plus 4-digit numbers that aren't in your list |
| **Vehicles** | Your dedicated vehicles with details (type, driver, mobile, transporter, route). Search, add/edit, import Excel |
| **Export** | Four Excel reports (below) and Clear entries |

## DMart Drishti gate log
**Today → Drishti gate log → Open** shows DMart Drishti inside the app. Log in on the site yourself once (the app never
sees or keeps the password). On **Vehicle Summary** the app reads every row of *Vehicle logs* (it scrolls and pages
through the list) and:
- **Checked In** marks the vehicle **Reported**, **Checked Out** marks it **Departed**, with the gate's times;
- a dedicated vehicle's replacement that checked in marks the dedicated vehicle;
- gate vehicles not in your list are added as **record only** (their last 4 digits then match WhatsApp posts);
  they get a Today mark only when someone posted them;
- a vehicle posted in WhatsApp but not yet at the gate stays **Yet to report**.

It reads once when Vehicle Summary opens, on **Pull now**, and by itself every 20 minutes while the app is open
(⋮ menu: site code, auto pull on/off, sign out). Only rows of the site code (default 1041) are taken.

## Excel reports
1. **Daily dedicated report** (pick a date): one row per vehicle with its details, Status (Reported / Not Reported / Check),
   first and last report time, who reported, group, times reported and message.
2. **Vehicle details**: the full vehicle list with details, whether it reported today, and when it last reported.
3. **Vehicle-wise summary** (date range): days reported, days not reported, days to check and attendance % for each vehicle.
4. **WhatsApp message log** (pick a date): every captured message.

## Import your vehicle list
Use **Vehicles → Import Excel / CSV**. Columns are found by their header names, so the order doesn't matter:
`Vehicle No`, `Vehicle Type`, `Driver Name`, `Driver Mobile`, `Transporter` (or Vendor/Owner), `Route` (or Location).
Start from `Vehicle_List_Template.xlsx` in this folder. Choose **Add / update** or **Replace list**.

## Build the APK
**Android Studio:** open this folder → wait for Gradle sync → `Build → Build APK(s)` →
`app/build/outputs/apk/debug/app-debug.apk`.

**GitHub, without Android Studio:** upload this folder to a GitHub repository. The included workflow
(`.github/workflows/build-apk.yml`) builds the APK automatically. Open **Actions** → the latest run →
download **Daily-Vehicle-Report-APK**.

If an older version is installed, uninstall it first.

## Phone setup
1. Open the app → **Enable** on the Today tab → turn ON **Daily Vehicle Report** in Notification Access.
2. **Vehicles** tab → add or import your vehicles.
3. **WhatsApp** tab → **Reading from → Change** → pick your one group (recommended). Groups appear in the list after one message arrives from them, or type the name.
4. Keep the WhatsApp group **not muted**.
5. Xiaomi / Oppo / Vivo / Realme / Samsung: allow **Autostart** and set battery to **No restrictions**.

## Limits
- Messages that arrive while that group is open on screen don't create a notification, so they are not read.
- Only messages received after setup are captured.
- Two vehicles with the same last 4 digits are marked **Check**. Ask those drivers to send the full number.
