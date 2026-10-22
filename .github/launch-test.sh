#!/bin/bash
# Called inside the emulator runner.
PKG=com.logistics.vehiclereport
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Centre of a view in the screen dump, by full resource id
centre() {
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "resource-id=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')
  set -- $b
  [ -n "$4" ] && echo "$(( ($1+$3)/2 )) $(( ($2+$4)/2 ))"
}
# Tap a view by its id; first close a slow-emulator "isn't responding" box with "Wait"
tapid() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  w=$(centre android:id/aerr_wait)
  if [ -n "$w" ]; then adb shell input tap $w; sleep 2; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; fi
  c=$(centre $PKG:id/$1)
  [ -n "$c" ] && adb shell input tap $c
}
# Tap the first view showing exactly this text
taptext() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')
  set -- $b
  [ -n "$4" ] && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
adb logcat -c
# Turn on notification access like the user does
adb shell cmd notification allow_listener $PKG/$PKG.WhatsAppNotificationListener || true
# Demo data and screenshots allowed (this test install only)
adb shell am start -W -n $PKG/.MainActivity; sleep 3; adb shell am force-stop $PKG
python3 .github/seed_demo.py
adb push /tmp/vehicle_cache.xml /data/local/tmp/vehicle_cache.xml
adb shell run-as $PKG sh -c "'mkdir -p shared_prefs && cat /data/local/tmp/vehicle_cache.xml > shared_prefs/vehicle_cache.xml'"
adb shell am start -W -n $PKG/.MainActivity
mkdir -p shots
# Sign-in screen (Play version): this test build has no Firebase settings, so use "this phone only".
sleep 4
SIZE=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb exec-out screencap -p > shots/00_sign_in.png
# The button sits below the fold on small screens: scroll down until it shows
for i in 1 2 3; do
  tapid localButton && break
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; sleep 1
done
sleep 1
# Opening animation frames
for n in 1 2 3 4 5; do sleep 1; adb exec-out screencap -p > shots/0_intro_$n.png; done
sleep 4
n=1
for id in nav_today nav_whatsapp nav_vehicles nav_export; do
  tapid $id; sleep 3; adb exec-out screencap -p > shots/${n}_$id.png; n=$((n+1))
done
# Mail opens from Export; Back returns to Export
tapid exportMail; sleep 3; adb exec-out screencap -p > shots/5_nav_mail.png
adb shell input keyevent KEYCODE_BACK; sleep 2; adb exec-out screencap -p > shots/5b_back_to_export.png
# Plan tab (stores seeded from a vehicle request sheet)
tapid nav_plan; sleep 3; adb exec-out screencap -p > shots/18_plan.png
tapid planSizes; sleep 2; adb exec-out screencap -p > shots/18e_plan_sizes.png; taptext "Cancel"; sleep 1
tapid planGeo; sleep 2; adb exec-out screencap -p > shots/18f_plan_map.png; taptext "Cancel"; sleep 1
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; sleep 2; adb exec-out screencap -p > shots/18b_plan_list.png
tapid psName; sleep 2; adb exec-out screencap -p > shots/18g_plan_store.png; taptext "Cancel"; sleep 1
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; sleep 2; adb exec-out screencap -p > shots/18c_plan_list.png
tapid pvSplit; sleep 2; adb exec-out screencap -p > shots/18d_plan_split.png
tapid nav_vehicles; sleep 2; tapid ratesButton; sleep 3; adb exec-out screencap -p > shots/6_rates.png
tapid liveChip; sleep 3; adb exec-out screencap -p > shots/7_live.png
# Consent screen shown before Accessibility settings open
taptext "Turn on"; sleep 2; adb exec-out screencap -p > shots/7b_consent_live.png; taptext "Not now"; sleep 1
tapid nav_today; sleep 3
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; sleep 2
adb exec-out screencap -p > shots/8_today_scrolled.png
# Today quick search
tapid todaySearch; sleep 1; adb shell input text "metro"; sleep 2; adb shell input keyevent KEYCODE_BACK; sleep 1
adb exec-out screencap -p > shots/8b_today_search.png
tapid todaySearch; sleep 1; adb shell input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DEL; done; adb shell input keyevent KEYCODE_BACK; sleep 1
# Yet to report / Reported / Departed on a Today row
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; sleep 2
adb exec-out screencap -p > shots/8c_today_marks.png
tapid gateReported; sleep 3; adb exec-out screencap -p > shots/8d_marked_reported.png
tapid gateDeparted; sleep 3; adb exec-out screencap -p > shots/8e_marked_departed.png
# "Yet to report" filter + search 6142: marking it Reported must keep the row (with its own selection), not drop it
tapid nav_today; sleep 2
for i in 1 2 3; do adb shell input swipe $((W/2)) $((H/3)) $((W/2)) $((H*3/4)) 300; done; sleep 1
tapid statYet; sleep 2
tapid todaySearch; sleep 1; adb shell input text "6142"; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; sleep 2
adb exec-out screencap -p > shots/8f_filter_yet.png
tapid gateReported; sleep 3; adb exec-out screencap -p > shots/8g_filter_yet_marked.png
tapid gateReported; sleep 3; adb exec-out screencap -p > shots/8h_filter_yet_cleared.png
tapid todaySearch; sleep 1; adb shell input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4; do adb shell input keyevent KEYCODE_DEL; done; adb shell input keyevent KEYCODE_BACK; sleep 1
for i in 1 2 3; do adb shell input swipe $((W/2)) $((H/3)) $((W/2)) $((H*3/4)) 300; done; sleep 1
tapid statYet; sleep 1
tapid nav_today; sleep 2
# Last 4 only: scan the number plate (typed here, the emulator camera has no plate to read)
adb shell pm grant $PKG android.permission.CAMERA || true
tapid nav_today; sleep 2
for i in 1 2 3; do adb shell input swipe $((W/2)) $((H/3)) $((W/2)) $((H*3/4)) 300; done; sleep 1
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/2)) 400; sleep 2
tapid chipLast4; sleep 2
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; sleep 2
adb exec-out screencap -p > shots/14_last4_only.png
tapid scanButton; sleep 6; adb exec-out screencap -p > shots/15_scanner.png
tapid scanType; sleep 1; adb shell input text "ts09ub4821"; sleep 1
adb exec-out screencap -p > shots/15b_scanner_typed.png
tapid scanUse; sleep 3; adb exec-out screencap -p > shots/16_scan_saved.png
taptext "Done"; sleep 3; adb exec-out screencap -p > shots/17_after_scan.png
tapid chipAll; sleep 1
tapid nav_today; sleep 2
# Vehicle details sheet, the form for pending details, and a search dropdown
tapid plateText; sleep 3; adb exec-out screencap -p > shots/9_details.png
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; sleep 2
adb exec-out screencap -p > shots/10_details_scrolled.png
adb shell input keyevent KEYCODE_BACK; sleep 2
tapid nav_vehicles; sleep 3
tapid plateText; sleep 3
tapid detPendingButton || tapid detEdit; sleep 3; adb exec-out screencap -p > shots/11_form.png
tapid transporterInput; sleep 2; adb exec-out screencap -p > shots/12_vendor_dropdown.png
adb shell input text "metro"; sleep 2; adb exec-out screencap -p > shots/13_vendor_search.png
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
sleep 2
adb logcat -d > logcat.txt
grep " E AndroidRuntime" logcat.txt | sed -E 's/^.* E AndroidRuntime: //' | grep -vE "^\s*at (android|java|com\.android|kotlin|androidx|dalvik|com\.google)\." | head -40 > crash.txt || true
if ! adb shell pidof $PKG >/dev/null; then
  echo "App process is not running after launch. Related system log:" >> crash.txt
  grep -E "ActivityManager|ActivityTaskManager|libc|DEBUG|lowmemorykiller|vehiclereport" logcat.txt | grep -iE "vehiclereport|died|kill|crash|anr|fatal" | tail -25 >> crash.txt
fi
cat crash.txt
exit 0
