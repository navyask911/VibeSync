package com.example.ui.model

import androidx.annotation.DrawableRes
import com.example.R

/** Bundled heritage line-art tiles, tinted for light/dark modes and dimmed behind chat content. */
enum class ChatWallpaperTheme(@DrawableRes val drawableRes: Int, val title: String, val subtitle: String, val plain: Boolean = false) {
    BENGALURU(R.drawable.wallpaper_capital_bengaluru, "Bengaluru", "Vidhana Soudha · Bangalore Palace · Nandi Bull · Indian Elephant · Indian Roller · Sandalwood Tree · Lotus"),
    DELHI(R.drawable.wallpaper_capital_new_delhi, "New Delhi", "India Gate · Parliament House · Red Fort · Lotus Temple · Nilgai · Ashoka Tree · Alstonia · Marigold"),
    MUMBAI(R.drawable.wallpaper_capital_mumbai, "Mumbai", "Gateway of India · CSMT Station · Sea Link · Indian Giant Squirrel · Yellow-footed Green Pigeon · Jarul Flower · Mango Tree"),
    CHENNAI(R.drawable.wallpaper_capital_chennai, "Chennai", "Central Railway Station · Ripon Building · Valluvar Kottam · Nilgiri Tahr · Emerald Dove · Palmyra Palm · Gloriosa Lily"),
    KOLKATA(R.drawable.wallpaper_capital_kolkata, "Kolkata", "Howrah Bridge · Victoria Memorial · Tram Cars · Fishing Cat · White-throated Kingfisher · Chatim Tree · Night-flowering Jasmine"),
    HYDERABAD(R.drawable.wallpaper_capital_hyderabad, "Hyderabad", "Charminar · Golconda Fort · Buddha Statue · Jinka (Deer) · Indian Roller (Palapitta) · Jammi Tree · Tangedu Flower"),
    JAIPUR(R.drawable.wallpaper_capital_jaipur, "Jaipur", "Hawa Mahal · Amer Fort · Jantar Mantar · Chinkara · Great Indian Bustard (Godawan) · Khejri Tree · Rohida Flower"),
    LUCKNOW(R.drawable.wallpaper_capital_lucknow, "Lucknow", "Rumi Darwaza · Bara Imambara · Swamp Deer · Sarus Crane · Sal Tree · Palash Flower"),
    BHUBANESWAR(R.drawable.wallpaper_capital_bhubaneswar, "Bhubaneswar", "Lingaraj Temple · Konark Sun Wheel · Mukteshwar Gate · Sambar Deer · Indian Roller · Kadamba Tree · Ashoka Flower"),
    THIRUVANANTHAPURAM(R.drawable.wallpaper_capital_thiruvananthapuram, "Thiruvananthapuram", "Padmanabhaswamy Temple · Houseboats · Kathakali Dancer · Indian Elephant · Great Hornbill · Coconut Palm · Kanikkonna"),
    PANAJI(R.drawable.wallpaper_capital_panaji, "Panaji", "Church of Our Lady of the Immaculate Conception · Aguada Fort · Mandovi Boats · Gaur · Flame-throated Bulbul · Matti Tree · Red Jasmine"),
    BHOPAL(R.drawable.wallpaper_capital_bhopal, "Bhopal", "Sanchi Stupa · Taj-ul-Masajid · Upper Lake Boats · Barasingha · Banyan Tree · Palash Flower"),
    GANDHINAGAR(R.drawable.wallpaper_capital_gandhinagar, "Gandhinagar", "Akshardham Temple · Adalaj Stepwell · Asiatic Lion · Banyan Tree · Marigold"),
    PATNA(R.drawable.wallpaper_capital_patna, "Patna", "Golghar · Mahabodhi Motifs · Nalanda Ruins · Gaur · House Sparrow · Peepal Tree · Kachnar"),
    RANCHI(R.drawable.wallpaper_capital_ranchi, "Ranchi", "Jagannath Temple · Hundru Falls · Asian Elephant · Sal Tree · Palash Flower"),
    RAIPUR(R.drawable.wallpaper_capital_raipur, "Raipur", "Bhoramdeo Motif · Bastar Bell-metal Art · Wild Water Buffalo · Sal Tree · Marigold"),
    CHANDIGARH(R.drawable.wallpaper_capital_chandigarh, "Chandigarh", "Open Hand Monument · Rock Garden Sculptures · Indian Grey Mongoose · Indian Grey Hornbill · Mango Tree · Dhak Flower"),
    SHIMLA(R.drawable.wallpaper_capital_shimla, "Shimla", "Christ Church · Viceregal Lodge · Snow Leopard · Western Tragopan (Jujurana) · Deodar Cedar · Pink Rhododendron"),
    DEHRADUN(R.drawable.wallpaper_capital_dehradun, "Dehradun", "Forest Research Institute · Clock Tower · Musk Deer · Himalayan Monal · Buransh (Rhododendron) · Brahma Kamal"),
    SRINAGAR(R.drawable.wallpaper_capital_srinagar, "Srinagar", "Shikara on Dal Lake · Shalimar Bagh · Chinar Leaves · Hangul (Kashmir Stag) · Chinar Tree · Lotus"),
    JAMMU(R.drawable.wallpaper_capital_jammu, "Jammu", "Jammu Heritage Motifs · Hill Fort · Dogra Art · Hangul · Chinar Tree · Lotus"),
    DISPUR(R.drawable.wallpaper_capital_dispur, "Dispur", "Kamakhya Temple Motifs · Brahmaputra Ferries · One-horned Rhinoceros · White-winged Wood Duck · Hollong Tree · Foxtail Orchid"),
    GUWAHATI(R.drawable.wallpaper_city_guwahati, "Guwahati", "Kamakhya Temple · Brahmaputra Ferries · One-horned Rhinoceros · Hollong Tree · Foxtail Orchid"),
    GANGTOK(R.drawable.wallpaper_capital_gangtok, "Gangtok", "Rumtek Monastery · Prayer Wheels · Red Panda · Rhododendron · Noble Dendrobium Orchid"),
    ITANAGAR(R.drawable.wallpaper_capital_itanagar, "Itanagar", "Ita Fort · Gompa Monastery · Hoolock Gibbon · Great Pied Hornbill · Hollong Tree · Foxtail Orchid"),
    IMPHAL(R.drawable.wallpaper_capital_imphal, "Imphal", "Kangla Fort · Shaheed Minar · Sangai (Brow-antlered Deer) · Nongin (Mrs Hume’s Pheasant) · Uningthou Tree · Shirui Lily"),
    SHILLONG(R.drawable.wallpaper_capital_shillong, "Shillong", "All Saints Church · Living Root Bridges · Clouded Leopard · White Cedar · Lady's Slipper Orchid"),
    AIZAWL(R.drawable.wallpaper_capital_aizawl, "Aizawl", "Solomon's Temple · Cheraw Bamboo Dancers · Himalayan Serow · Mrs Hume’s Pheasant · Ironwood Tree · Red Vanda"),
    KOHIMA(R.drawable.wallpaper_capital_kohima, "Kohima", "Kohima War Cemetery · Hornbill Tribal Motifs · Mithun (State Animal) · Blyth’s Tragopan (State Bird) · Alder Tree · Rhododendron"),
    AGARTALA(R.drawable.wallpaper_capital_agartala, "Agartala", "Ujjayanta Palace · Neermahal · Phayre’s Leaf Monkey · Green Imperial Pigeon · Agarwood Tree · Nageshwar Flower"),
    AMARAVATI(R.drawable.wallpaper_capital_amaravati, "Amaravati", "Amaravati Stupa · Krishna River · Buddhist Stone Motifs · Blackbuck · Rose-ringed Parakeet · Neem Tree · Jasmine"),
    PORT_BLAIR(R.drawable.wallpaper_capital_sri_vijaya_puram, "Sri Vijaya Puram", "Cellular Jail · Harbour Boats · Island Coral · Sea Eagle · Coconut Palm · Hibiscus"),
    DAMAN(R.drawable.wallpaper_capital_daman, "Daman", "Moti Daman Fort · Lighthouse · Arabian Sea Boats · Coastal Bird Motifs · Coconut Palm · Hibiscus"),
    SILVASSA(R.drawable.wallpaper_city_silvassa, "Silvassa", "Warli Folk Art · Tribal Museum Motifs · Gaur · Teak Forests · Palash Flower"),
    KAVARATTI(R.drawable.wallpaper_capital_kavaratti, "Kavaratti", "Lagoon Boats · Coral Reefs · Green Sea Turtle · Coconut Palm · Island Flowers"),
    PUDUCHERRY(R.drawable.wallpaper_capital_puducherry, "Puducherry", "French Quarter · Seafront Promenade · Tamil Heritage Motifs · Banyan Tree · Hibiscus"),
    LEH(R.drawable.wallpaper_capital_leh, "Leh", "Leh Palace · Monastery Silhouette · Mountain Passes · Snow Leopard · Willow · Blue Poppy"),
    GAIRSAIN(R.drawable.wallpaper_capital_gairsain, "Gairsain", "Himalayan Ridge Motifs · Traditional Hill Roofs · Musk Deer · Himalayan Monal · Buransh (Rhododendron) · Brahma Kamal"),
    MYSURU(R.drawable.wallpaper_city_mysuru, "Mysuru", "Mysore Palace · Chamundi Hills · Sandalwood motifs"),
    KOCHI(R.drawable.wallpaper_city_kochi, "Kochi", "Chinese fishing nets · Backwaters · Coconut palm"),
    VARANASI(R.drawable.wallpaper_city_varanasi, "Varanasi", "Ghats · Temple bells · Ganga boats · Lotus"),
    GOA(R.drawable.wallpaper_city_goa, "Goa", "Portuguese facades · Palm silhouettes · Coastal flowers"),
    PUNE(R.drawable.wallpaper_city_pune, "Pune", "Historic gateways · Wada details · Deccan foliage"),
    AHMEDABAD(R.drawable.wallpaper_city_ahmedabad, "Ahmedabad", "Stepwell geometry · Pol houses · Carved screens"),
    UDAIPUR(R.drawable.wallpaper_city_udaipur, "Udaipur", "Lake palace arches · Aravalli hills · Lotus"),
    AMRITSAR(R.drawable.wallpaper_city_amritsar, "Amritsar", "Heritage gateways · Sarovar waves · Folk motifs"),
    NEUTRAL(R.drawable.wallpaper_city_neutral, "Neutral line art", "Soft abstract foliage, curves, dots and geometry"),
    PLAIN(R.drawable.wallpaper_city_plain, "Plain", "A clean solid background")
}
data class ChatWallpaperUiState(
    val theme: ChatWallpaperTheme = ChatWallpaperTheme.BENGALURU,
    val isDarkMode: Boolean = false,
    val wallpaperOpacity: Float = 0.11f
) {
    val safeOpacity: Float get() = wallpaperOpacity.coerceIn(0.05f, 0.12f)
}
