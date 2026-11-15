package com.logistics.vehiclereport

/**
 * The DC's store list (code, name, circle, city) as read from the sheet photos; used until a store list is imported
 * on the Vehicles tab (an imported list replaces it). Circle goes to Store.region, the sheet's City column to Store.city.
 */
object DefaultStores {
    private const val CG = "CG"; private const val T1 = "Telangana 1"; private const val T2 = "Telangana 2"
    private const val A1 = "AP-1"; private const val A2 = "AP-2"; private const val A3 = "AP-3"

    private val rows = listOf(
        "4901|City Mall Raipur D-Mart|$CG|Chhattisgarh", "4902|NH-6 Bhilai D-Mart|$CG|Chhattisgarh",
        "4903|Ring Road Raipur D-Mart|$CG|Chhattisgarh", "4904|Ambadi Nagar Bhilai D-Mart|$CG|Chhattisgarh",
        "4905|Rajnandgaon Chhattisgarh D-Mart|$CG|Chhattisgarh", "4906|Potiyakala Durg Chhattisgarh DMart|$CG|Chhattisgarh",
        "4907|Devpuri, Raipur|$CG|Chhattisgarh", "4952|Sarkhanda, Bilaspur|$CG|Chhattisgarh",
        "5001|NSL D-Mart|$T1|Local", "5002|PSR D-Mart|$T1|Local", "5003|Amberpet D-Mart|$T1|Local",
        "5005|Champapet D-Mart|$T1|Local", "5006|Malakpet D-Mart|$T1|Local",
        "5007|Ramchandrapuram Hyderabad D-Mart|$T1|Local", "5008|Safilaguda Secunderabad DMart|$T1|Local",
        "5009|Bandlaguda Hyderabad DMart|$T1|Local", "5010|Myhome D-Mart|$T1|Local", "5011|Khushaiguda Hyd D-Mart|$T1|Local",
        "5013|S.D.Road Hyderabad D-Mart|$T1|Local", "5014|LB Nagar D-Mart|$T1|Local",
        "5015|Ramanthpur Hyderabad D-Mart|$T1|Local", "5016|Warangal D-Mart|$T2|ROTS", "5017|Karim Nagar D-Mart|$T2|ROTS",
        "5018|Himayat Nagar Hyderabad D-Mart|$T1|Local", "5019|Shaikpet Hyderabad D-Mart|$T1|Local",
        "5020|Kaveri Hills HYD D-Mart|$T1|Local", "5021|Kompally Hyderabad D-Mart|$T1|Local",
        "5022|Madinaguda Hyderabad D-Mart|$T1|Local", "5024|Medipally Hyderabad DMart|$T1|Local",
        "5025|Upperpally Hyderabad D-Mart|$T1|Local", "5026|Khammam Telangana D-Mart|$T2|ROTS",
        "5027|Balanagar Hyderabad D-Mart|$T1|Local", "5028|Vanasthalipuram Hyderabad D-Mart|$T1|Local",
        "5029|Laxmipuram Warangal D-Mart|$T2|ROTS", "5030|Kachiguda Hyderabad D-Mart|$T1|Local",
        "5034|Nizampet Medchal Telangana DMart|$T1|Local", "5037|Rampur Karimnagar Telangana|$T2|ROTS",
        "5038|Mancherial Adilabad Telangana DMart|$T2|ROTS", "5040|Uppal Bahg Medchal Mal TL DMart|$T1|Local",
        "5042|Suryapet Nalgonda DMart|$T2|ROTS", "5043|Nalla Gandla Ranga Reddy DMart|$T1|Local",
        "5044|Narsingi Ranga Reddy|$T1|Local", "5045|Annajiguda Medchal DMart|$T1|Local",
        "5046|Khanaji Medchal DMart|$T1|Local", "5047|Kadipikonda Warangal DMart|$T2|ROTS",
        "5048|Gachibowli Hyderabad Telangana|$T1|Local", "5049|Hafeezpet Ranga Reddy|$T1|Local",
        "5051|Miryalaguda Nalgonda TL|$T2|ROTS", "5052|Uppal Ranga Reddy TL Dmart|$T1|Local",
        "5055|Gandhamguda Hyderabad Telangana|$T1|Local", "5056|Nirmal Telangana|$T2|ROTS",
        "5057|Jagtial Telangana|$T2|ROTS", "5058|Goopanpally Nizamabad Telangana|$T2|ROTS",
        "5060|Turkayamjal, Hyderabad|$T2|ROTS", "5063|Kodad|$T1|Local", "5066|Adilabad|$T2|ROTS",
        "5102|Kurnool D-Mart|$A1|Andhra Pradesh", "5107|Joharapuram Kurnool AP|$A1|Andhra Pradesh",
        "5110|Adoni|$A1|Andhra Pradesh", "5201|VIJAYAWADA D_MART|$A1|Andhra Pradesh",
        "5203|Gudivada AP D-Mart|$A1|Andhra Pradesh", "5204|Benz Circle Vijaywada Dmart|$A1|Andhra Pradesh",
        "5205|Eedepalli Krishna AP DMart|$A1|Andhra Pradesh", "5206|Airport Road Vijayawada D-Mart|$A1|Andhra Pradesh",
        "5207|Gollapudi Vijaywada DMart|$A1|Andhra Pradesh", "5209|Nunna Krishna DMart|$A1|Andhra Pradesh",
        "5210|Nuzividu|$A1|Andhra Pradesh", "5221|Naaz Centre Guntur AP|$A1|Andhra Pradesh",
        "5222|Guntur AP D-Mart|$A1|Andhra Pradesh", "5223|Narasaraopeta Guntur AP|$A1|Andhra Pradesh",
        "5224|Kunchanapally Guntur DMart|$A1|Andhra Pradesh", "5225|Chikalarupeta Guntur|$A1|Andhra Pradesh",
        "5227|Ganguru Vijayawada AP DMart|$A1|Andhra Pradesh", "5231|Ongole D-Mart|$A1|Andhra Pradesh",
        "5232|Chirala|$A1|Andhra Pradesh", "5301|Madhurwada Vizag D-Mart|$A3|Andhra Pradesh",
        "5302|Marripalem Visakhapatnam|$A3|Andhra Pradesh", "5303|Gajuwaka Vizag D-Mart|$A3|Andhra Pradesh",
        "5304|Suryabagh Vizag DMart|$A3|Andhra Pradesh", "5305|Kancharapalem, Vizag|$A3|Andhra Pradesh",
        "5306|Anakapalle Visakhapatnam AP|$A3|Andhra Pradesh", "5307|Sheelanagar|$A3|Andhra Pradesh",
        "5308|Visakhapatnam-MVP Colony|$A3|Andhra Pradesh", "5331|Kakinada D-Mart|$A2|Andhra Pradesh",
        "5332|Eluru West Godavari AP D-Mart|$A1|Andhra Pradesh", "5333|Ramadaspeta Rajahmundry AP DMart|$A2|Andhra Pradesh",
        "5334|Hukumpeta Rajahmundry AP|$A2|Andhra Pradesh", "5335|Tuni Vizag D-Mart|$A3|Andhra Pradesh",
        "5336|Bhimavaram Dmart|$A2|Andhra Pradesh", "5337|Tanuku West Godavari D-Mart|$A2|Andhra Pradesh",
        "5338|Vepagunta Vizag|$A3|Andhra Pradesh", "5339|Turnagi Kakinada AP DMart|$A2|Andhra Pradesh",
        "5340|Tadepalligudem|$A2|Andhra Pradesh", "5351|Agraharam Vizianagaram AP DMart|$A3|Andhra Pradesh",
        "5801|Humnabad Gulbarga Dmart|BLR|Bangalore", "5841|Askihal Raichur|BLR|Bangalore",
        "5871|Bagalkot Karnataka|BLR|Bangalore", "7531|SGBL Square Mall, Cuttack|ODISHA|Odisha"
    )

    val stores: List<Store> = rows.map { r -> r.split("|").let { Store(it[0], it[1], it[3], it[2]) } }
}
