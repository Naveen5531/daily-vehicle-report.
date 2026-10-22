"""Writes demo data + 'screenshots allowed' into the app's preferences, for the emulator screenshots only.
Play version: every name, number and vendor here is made up (the screenshots go on the public store page)."""
import json, time, html
day = int(time.time()) // 86400 * 86400 - 5 * 3600 - 30 * 60  # midnight IST
def t(h, m): return (day + h * 3600 + m * 60) * 1000
vehicles = [
    ("TS09UB4821", "709", "SUNRISE LOGISTICS"), ("TS08UC3307", "110A", "SUNRISE LOGISTICS"), ("AP28TD6142", "110A", "SUNRISE LOGISTICS"),
    ("TS12UE2290", "110A", "SUNRISE LOGISTICS"), ("TS10UA5568", "909", "BLUE LINE TRANSPORT"), ("AP29TC7731", "110A", "BLUE LINE TRANSPORT"),
    ("TS07UF1846", "407", "METRO CARGO MOVERS"), ("TS11UB9024", "110A", "METRO CARGO MOVERS"), ("AP39TE3375", "110A", "METRO CARGO MOVERS"),
    ("TS13UD6609", "110A", "GREENWAY CARRIERS"), ("TS15UA2148", "110A", "GREENWAY CARRIERS"), ("TS08UD7470", "407", "GREENWAY CARRIERS"),
    ("TS09UE1192", "110A", "GREENWAY CARRIERS"), ("TS10UC8853", "110A", "SWIFT ROADWAYS"), ("AP28TF4416", "909", "SWIFT ROADWAYS"),
    ("TS12UF5037", "110A", "SWIFT ROADWAYS"),
]
ded = [{"number": n, "type": ty, "driver": "", "mobile": "", "transporter": tr, "route": "", "ded": True} for n, ty, tr in vehicles]
m1 = "4821 - Kukatpally 709\n3307 - Miyapur\n6142 - Bachupally\nSunrise logistics"
m2 = "Ts07uf 1846 Uppal 407\nTs11ub 9024 Nagole\nMetro cargo"
m3 = "5568 Kompally 909\n7731 Medchal\nBlue line transport"
posts = [
    ("TS09UB4821", "4821", "LAST4", t(8, 41), "Kukatpally", "", "Sunrise Logistics", "709", m1),
    ("TS08UC3307", "3307", "LAST4", t(8, 41), "Miyapur", "", "Sunrise Logistics", "", m1),
    ("AP28TD6142", "6142", "LAST4", t(8, 41), "Bachupally", "", "Sunrise Logistics", "", m1),
    ("TS07UF1846", "TS07UF1846", "FULL", t(8, 47), "Uppal", "", "Metro Cargo", "407", m2),
    ("TS11UB9024", "TS11UB9024", "FULL", t(8, 47), "Nagole", "", "Metro Cargo", "", m2),
    ("TS10UA5568", "5568", "LAST4", t(8, 52), "Kompally", "", "Blue Line Transport", "909", m3),
    ("AP29TC7731", "7731", "LAST4", t(8, 52), "Medchal", "", "Blue Line Transport", "", m3),
    ("TS13UD6609", "6609", "LAST4", t(9, 5), "LB Nagar", "", "Greenway Carriers", "", "LB Nagar 6609\nGreenway carriers"),
    ("TS10UC8853", "TS10UC8853", "FULL", t(10, 8), "Shamshabad", "9876543210", "Swift Roadways", "", "TS10UC8853\n9876543210\nShamshabad\nSwift roadways"),
    ("TS12UE2290", "TS12UE2290", "FULL", t(10, 12), "Gachibowli", "9876501234", "Sunrise Logistics", "", "TS12UE2290\n98765 01234\nGachibowli\nSunrise logistics"),
    ("TG07W5521", "TG07W5521", "FULL", t(10, 20), "Vijayawada", "9876512345", "Swift Roadways", "22 FT", "TG07W5521.\nVIJAYAWADA 22FT 09876512345\nSwift roadways"),
    ("AP28TF4416", "4416", "LAST4", t(10, 31), "Madhapur", "9876523456", "Swift Roadways", "", "4416 Madhapur\n9876523456\nSwift roadways"),
]
recs = []
for i, (v, typed, match, tm, city, mob, vendor, size, msg) in enumerate(posts):
    recs.append({"id": f"demo{i}", "vehicle": v, "chat": "DC Transport Team", "sender": "Ravi Dispatch", "message": msg,
                 "time": tm, "match": match, "typed": typed, "candidates": [v], "city": city, "replacement": "", "mobile": mob,
                 "vendor": vendor, "size": size, "driver": "", "count": 1, "last": tm, "ss": ["Ravi Dispatch"]})
# Hand marks on Today (Reported / Departed), on the emulator's date (UTC)
today_key = time.strftime("%Y-%m-%d", time.gmtime())
marks = {today_key: {
    "TS09UB4821": ["D", t(9, 2), t(10, 15)], "TS11UB9024": ["D", t(9, 25), t(10, 40)],
    "TS08UC3307": ["R", t(9, 10), 0], "TS07UF1846": ["R", t(9, 20), 0], "TS10UA5568": ["R", t(9, 31), 0],
}}
since = (int(time.time()) // 86400 - 2) * 86400 * 1000
# A vehicle request sheet for the Plan tab (volumes as on a real day; store names made up)
sheet = [
    ("5043", 1436.065, 4529.495, 592, 2, "Telangana 1"), ("5034", 1033.391, 4284.082, 489, 2, "Telangana 1"),
    ("5025", 1012.626, 4346.69, 481, 2, "Telangana 1"), ("5055", 947.895, 4313.351, 565, 1, "Telangana 1"),
    ("5022", 878.185, 3162.709, 375, 1, "Telangana 1"), ("5027", 804.519, 3045.592, 406, 1, "Telangana 1"),
    ("5002", 794.309, 2684.676, 341, 1, "Telangana 1"), ("5005", 734.959, 3045.881, 366, 1, "Telangana 1"),
    ("5021", 319.912, 1454.726, 179, 1, "Telangana 1"), ("5030", 205.492, 947.08, 110, 0, "Telangana 1"),
    ("5009", 175.521, 882.05, 90, 0, "Telangana 1"), ("5029", 1129.891, 5003.917, 479, 2, "Telangana 2"),
    ("5026", 932.441, 4378.601, 439, 1, "Telangana 2"), ("5051", 611.506, 2828.187, 276, 1, "Telangana 2"),
    ("5063", 23.58, 440, 22, 0, "Telangana 2"),
]
plan = {"created": int(time.time()) * 1000, "stops": [
    {"code": c, "name": f"Store {c}", "vol": v, "wt": w, "box": b, "veh": n, "circle": ci, "remark": "Only Cons." if v < 50 else ""}
    for c, v, w, b, n, ci in sheet], "noClub": [], "clubs": [], "sources": [{"label": "Photo 1", "rows": len(sheet), "read": sum(x[1] for x in sheet)}]}
# Made-up map places for the demo stores (a city group around the DC, and a long road east with one stop on the way)
geo_pts = {"5043": (17.44, 78.40), "5034": (17.40, 78.48), "5025": (17.36, 78.55), "5055": (17.48, 78.32), "5022": (17.50, 78.40),
           "5027": (17.42, 78.35), "5002": (17.35, 78.45), "5005": (17.34, 78.51), "5021": (17.33, 78.56), "5030": (17.345, 78.515),
           "5009": (17.39, 78.50), "5029": (17.97, 79.59), "5026": (16.51, 80.64), "5051": (17.25, 80.15), "5063": (16.99, 79.97)}
store_geo = {c: [la, lo, f"Demo area {c}", 0] for c, (la, lo) in geo_pts.items()}
def s(k, v): return f'<string name="{k}">{html.escape(v, quote=True)}</string>'
xml = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>",
       '<boolean name="security_lock" value="false" />', '<boolean name="security_block_screens" value="false" />',
       '<int name="records_version" value="99" />',
       s("dedicated_json", json.dumps(ded)), s("records_json", json.dumps(recs)), s("selected_group", "DC Transport Team"), s("dc_name", "Central DC"),
       '<boolean name="dc_asked" value="true" />', s("gate_marks_json", json.dumps(marks)), s("plan_json", json.dumps(plan)),
       s("store_geo_json", json.dumps(store_geo)), s("dc_geo_json", json.dumps([17.55, 78.25, "Central"])), f'<long name="gate_since" value="{since}" />', "</map>"]
open("/tmp/vehicle_cache.xml", "w").write("\n".join(xml))
print(len(ded), "vehicles,", len(recs), "entries")
