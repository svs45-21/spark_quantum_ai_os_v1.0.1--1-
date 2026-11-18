package com.spark.companion.ui.avatar

import org.json.JSONObject

enum class Gender { FEMALE, MALE }

enum class Top { TEE, SHIRT, BLOUSE, TANK, HOODIE, SWEATER, TURTLENECK, POLO, KURTA, CROP, CHOLI, SPORTS, SCRUBS }
enum class Sleeve { NONE, SHORT, ELBOW, LONG }
enum class Neck { ROUND, V, COLLAR, HIGH, SQUARE, MANDARIN }
enum class Bottom { JEANS, TROUSERS, JOGGERS, SHORTS, SKIRT, PENCIL, LONG_SKIRT, LEHENGA, LEGGINGS, PALAZZO, DHOTI, CHURIDAR }
enum class Outer { NONE, JACKET, DENIM, LEATHER, BLAZER, COAT, CARDIGAN, LAB_COAT, SHERWANI, NEHRU, VEST, DUPATTA, PALLU, SHAWL, CAPE, APRON, ROBE }
enum class Pattern { PLAIN, STRIPES, CHECKS, DOTS, FLORAL, SEQUIN, BORDER, EMBROIDERY }
enum class Shoes { SNEAKERS, FORMAL, HEELS, BOOTS, SANDALS, FLATS, JUTTI }
enum class Extra {
    TIE, BOW_TIE, BELT, SCARF, NECKLACE, EARRINGS, BINDI, GLASSES, SUNGLASSES, CAP, BEANIE, HAT,
    WATCH, HEADPHONES, STETHOSCOPE, BADGE, CHEF_HAT, GRAD_CAP, MAANG_TIKKA, BANGLES, POCKET_SQUARE, SAFA,
}

/** One complete look. Colours are ARGB ints. */
data class Outfit(
    val id: String,
    val name: String,
    val gender: Gender,
    val top: Top,
    val sleeve: Sleeve,
    val neck: Neck,
    /** Where the top ends, as a fraction of body height (0.36 crop … 0.85 calf). */
    val topLength: Float,
    val bottom: Bottom,
    val outer: Outer,
    val topColor: Int,
    val bottomColor: Int,
    val outerColor: Int,
    val accent: Int,
    val shoeColor: Int,
    val pattern: Pattern = Pattern.PLAIN,
    val shoes: Shoes = Shoes.SNEAKERS,
    val extras: Set<Extra> = emptySet(),
    /** Top and skirt are one piece (dresses and gowns). */
    val dress: Boolean = false,
)

/** The outfit catalogue: 50 looks for each body type. */
object Outfits {
    private fun c(hex: String) = android.graphics.Color.parseColor(hex)

    private const val CROP = 0.36f
    private const val WAIST = 0.43f
    private const val HIP = 0.5f
    private const val THIGH = 0.6f
    private const val KNEE = 0.72f
    private const val CALF = 0.84f

    private fun f(
        id: String, name: String, top: Top, sleeve: Sleeve, neck: Neck, len: Float, bottom: Bottom, outer: Outer,
        topC: String, bottomC: String, outerC: String, accent: String, shoeC: String,
        pattern: Pattern = Pattern.PLAIN, shoes: Shoes = Shoes.FLATS, extras: Set<Extra> = emptySet(), dress: Boolean = false,
    ) = Outfit("f-$id", name, Gender.FEMALE, top, sleeve, neck, len, bottom, outer, c(topC), c(bottomC), c(outerC), c(accent), c(shoeC), pattern, shoes, extras, dress)

    private fun m(
        id: String, name: String, top: Top, sleeve: Sleeve, neck: Neck, len: Float, bottom: Bottom, outer: Outer,
        topC: String, bottomC: String, outerC: String, accent: String, shoeC: String,
        pattern: Pattern = Pattern.PLAIN, shoes: Shoes = Shoes.SNEAKERS, extras: Set<Extra> = emptySet(),
    ) = Outfit("m-$id", name, Gender.MALE, top, sleeve, neck, len, bottom, outer, c(topC), c(bottomC), c(outerC), c(accent), c(shoeC), pattern, shoes, extras)

    val FEMALE: List<Outfit> by lazy {
        listOf(
            f("tee-jeans", "Tee & jeans", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.NONE, "#F4F1EA", "#3B5B8C", "#000000", "#E05A47", "#FFFFFF", shoes = Shoes.SNEAKERS),
            f("denim-jacket", "Denim jacket", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.DENIM, "#FFFFFF", "#2A2F3A", "#5F86B8", "#F2C14E", "#E9E4DA", shoes = Shoes.SNEAKERS),
            f("sundress", "Floral sundress", Top.TANK, Sleeve.NONE, Neck.SQUARE, WAIST, Bottom.SKIRT, Outer.NONE, "#F7D9E3", "#F7D9E3", "#000000", "#E87A9B", "#D9B48F", Pattern.FLORAL, Shoes.SANDALS, dress = true),
            f("lbd", "Little black dress", Top.TANK, Sleeve.NONE, Neck.V, WAIST, Bottom.SKIRT, Outer.NONE, "#16161C", "#16161C", "#000000", "#D4AF37", "#16161C", shoes = Shoes.HEELS, extras = setOf(Extra.NECKLACE, Extra.EARRINGS), dress = true),
            f("gown-red", "Red evening gown", Top.TANK, Sleeve.NONE, Neck.V, WAIST, Bottom.LONG_SKIRT, Outer.NONE, "#A3122A", "#A3122A", "#000000", "#F1D17A", "#A3122A", Pattern.SEQUIN, Shoes.HEELS, setOf(Extra.EARRINGS, Extra.NECKLACE), dress = true),
            f("gown-blue", "Midnight ball gown", Top.TANK, Sleeve.NONE, Neck.SQUARE, WAIST, Bottom.LEHENGA, Outer.NONE, "#1E2B6E", "#1E2B6E", "#000000", "#C9D4FF", "#1E2B6E", Pattern.SEQUIN, Shoes.HEELS, setOf(Extra.EARRINGS), dress = true),
            f("office-blazer", "Office blazer", Top.BLOUSE, Sleeve.LONG, Neck.V, HIP, Bottom.TROUSERS, Outer.BLAZER, "#FFFFFF", "#2D3142", "#2D3142", "#B8C1EC", "#1A1A1A", shoes = Shoes.HEELS, extras = setOf(Extra.WATCH)),
            f("pencil-suit", "Pencil skirt suit", Top.BLOUSE, Sleeve.LONG, Neck.COLLAR, WAIST, Bottom.PENCIL, Outer.BLAZER, "#F5EFE6", "#5B5F97", "#5B5F97", "#FFC145", "#2B2B2B", shoes = Shoes.HEELS, extras = setOf(Extra.EARRINGS)),
            f("hoodie", "Cosy hoodie", Top.HOODIE, Sleeve.LONG, Neck.ROUND, HIP, Bottom.JOGGERS, Outer.NONE, "#9B8AF2", "#3A3A48", "#000000", "#FFFFFF", "#FFFFFF", shoes = Shoes.SNEAKERS),
            f("yoga", "Yoga set", Top.SPORTS, Sleeve.NONE, Neck.SQUARE, CROP, Bottom.LEGGINGS, Outer.NONE, "#2EC4B6", "#22313F", "#000000", "#FFFFFF", "#F0F0F0", shoes = Shoes.SNEAKERS),
            f("runner", "Running kit", Top.TANK, Sleeve.NONE, Neck.ROUND, HIP, Bottom.SHORTS, Outer.NONE, "#FF6B35", "#1B1B2F", "#000000", "#FFFFFF", "#FF6B35", shoes = Shoes.SNEAKERS, extras = setOf(Extra.WATCH, Extra.HEADPHONES)),
            f("sweater", "Knit sweater", Top.SWEATER, Sleeve.LONG, Neck.ROUND, HIP, Bottom.JEANS, Outer.NONE, "#C9A27E", "#30405F", "#000000", "#F3E9DC", "#5C4033", Pattern.STRIPES, Shoes.BOOTS),
            f("cardigan", "Cardigan & skirt", Top.TEE, Sleeve.SHORT, Neck.ROUND, WAIST, Bottom.SKIRT, Outer.CARDIGAN, "#FFFFFF", "#7A4E8C", "#E9D5A1", "#7A4E8C", "#7A4E8C", shoes = Shoes.FLATS),
            f("saree-red", "Red silk saree", Top.CHOLI, Sleeve.SHORT, Neck.ROUND, CROP, Bottom.LONG_SKIRT, Outer.PALLU, "#B3122E", "#B3122E", "#B3122E", "#E8B54A", "#E8B54A", Pattern.BORDER, Shoes.JUTTI, setOf(Extra.BINDI, Extra.EARRINGS, Extra.BANGLES, Extra.NECKLACE)),
            f("saree-gold", "Banarasi gold saree", Top.CHOLI, Sleeve.ELBOW, Neck.ROUND, CROP, Bottom.LONG_SKIRT, Outer.PALLU, "#7A1E3A", "#D9A441", "#D9A441", "#7A1E3A", "#D9A441", Pattern.BORDER, Shoes.JUTTI, setOf(Extra.BINDI, Extra.EARRINGS, Extra.BANGLES, Extra.MAANG_TIKKA)),
            f("saree-blue", "Cotton saree", Top.CHOLI, Sleeve.SHORT, Neck.ROUND, CROP, Bottom.LONG_SKIRT, Outer.PALLU, "#F5F5F5", "#2F6690", "#2F6690", "#F5F5F5", "#C9A27E", Pattern.BORDER, Shoes.SANDALS, setOf(Extra.BINDI, Extra.EARRINGS)),
            f("saree-green", "Kanjivaram green saree", Top.CHOLI, Sleeve.ELBOW, Neck.ROUND, CROP, Bottom.LONG_SKIRT, Outer.PALLU, "#C2185B", "#1B7F4C", "#1B7F4C", "#E8B54A", "#E8B54A", Pattern.BORDER, Shoes.JUTTI, setOf(Extra.BINDI, Extra.EARRINGS, Extra.NECKLACE, Extra.BANGLES)),
            f("kurti", "Kurti & leggings", Top.KURTA, Sleeve.ELBOW, Neck.V, KNEE, Bottom.CHURIDAR, Outer.NONE, "#F2A541", "#FFF4E0", "#000000", "#B5442F", "#B5442F", Pattern.FLORAL, Shoes.JUTTI, setOf(Extra.EARRINGS, Extra.BINDI)),
            f("anarkali", "Anarkali", Top.KURTA, Sleeve.LONG, Neck.ROUND, CALF, Bottom.CHURIDAR, Outer.DUPATTA, "#5E2B97", "#F1E3FF", "#D9C2F2", "#E8B54A", "#E8B54A", Pattern.EMBROIDERY, Shoes.JUTTI, setOf(Extra.EARRINGS, Extra.BANGLES), dress = true),
            f("salwar", "Salwar kameez", Top.KURTA, Sleeve.ELBOW, Neck.ROUND, KNEE, Bottom.PALAZZO, Outer.DUPATTA, "#E6F2E6", "#3E8E7E", "#3E8E7E", "#E07A5F", "#E07A5F", Pattern.DOTS, Shoes.JUTTI, setOf(Extra.EARRINGS, Extra.BINDI)),
            f("lehenga-bridal", "Bridal lehenga", Top.CHOLI, Sleeve.ELBOW, Neck.ROUND, CROP, Bottom.LEHENGA, Outer.DUPATTA, "#9E0F2A", "#9E0F2A", "#C2185B", "#F1C24B", "#F1C24B", Pattern.SEQUIN, Shoes.JUTTI, setOf(Extra.MAANG_TIKKA, Extra.EARRINGS, Extra.NECKLACE, Extra.BANGLES, Extra.BINDI)),
            f("lehenga-festive", "Festive lehenga", Top.CHOLI, Sleeve.SHORT, Neck.SQUARE, CROP, Bottom.LEHENGA, Outer.DUPATTA, "#F7B32B", "#2D7DD2", "#F45D01", "#F7B32B", "#F7B32B", Pattern.BORDER, Shoes.JUTTI, setOf(Extra.EARRINGS, Extra.BANGLES, Extra.BINDI)),
            f("palazzo", "Palazzo set", Top.KURTA, Sleeve.NONE, Neck.V, THIGH, Bottom.PALAZZO, Outer.NONE, "#F28482", "#F6BD60", "#000000", "#FFFFFF", "#F6BD60", Pattern.FLORAL, Shoes.SANDALS, setOf(Extra.EARRINGS)),
            f("indo-western", "Indo-western", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, KNEE, Bottom.TROUSERS, Outer.SHAWL, "#264653", "#E9C46A", "#E76F51", "#E9C46A", "#2A2A2A", shoes = Shoes.HEELS, extras = setOf(Extra.EARRINGS)),
            f("doctor", "Doctor", Top.BLOUSE, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.LAB_COAT, "#BFD7EA", "#2E3A59", "#FFFFFF", "#4C9F70", "#2E3A59", shoes = Shoes.FLATS, extras = setOf(Extra.STETHOSCOPE, Extra.BADGE)),
            f("scrubs", "Medical scrubs", Top.SCRUBS, Sleeve.SHORT, Neck.V, HIP, Bottom.JOGGERS, Outer.NONE, "#3AAFA9", "#3AAFA9", "#000000", "#FFFFFF", "#FFFFFF", shoes = Shoes.SNEAKERS, extras = setOf(Extra.BADGE, Extra.STETHOSCOPE)),
            f("chef", "Chef", Top.SHIRT, Sleeve.LONG, Neck.MANDARIN, HIP, Bottom.TROUSERS, Outer.APRON, "#FFFFFF", "#2B2B2B", "#F5F5F5", "#D62828", "#2B2B2B", shoes = Shoes.FLATS, extras = setOf(Extra.CHEF_HAT)),
            f("pilot", "Pilot", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#FFFFFF", "#1D2D50", "#1D2D50", "#E0B84A", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE, Extra.BADGE, Extra.HAT)),
            f("school", "School uniform", Top.SHIRT, Sleeve.SHORT, Neck.COLLAR, WAIST, Bottom.SKIRT, Outer.VEST, "#FFFFFF", "#24385C", "#24385C", "#B22234", "#111111", Pattern.CHECKS, Shoes.FORMAL, setOf(Extra.TIE)),
            f("college", "College casual", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.JEANS, Outer.NONE, "#C0392B", "#2C3E50", "#000000", "#FFFFFF", "#ECECEC", Pattern.CHECKS, Shoes.SNEAKERS, setOf(Extra.WATCH)),
            f("party", "Party sequins", Top.TANK, Sleeve.NONE, Neck.V, WAIST, Bottom.SKIRT, Outer.NONE, "#C0C0C8", "#C0C0C8", "#000000", "#FF4FA3", "#C0C0C8", Pattern.SEQUIN, Shoes.HEELS, setOf(Extra.EARRINGS), dress = true),
            f("boho", "Boho maxi", Top.BLOUSE, Sleeve.ELBOW, Neck.V, WAIST, Bottom.LONG_SKIRT, Outer.NONE, "#E9D8A6", "#CA6702", "#000000", "#005F73", "#8C5A3C", Pattern.FLORAL, Shoes.SANDALS, setOf(Extra.NECKLACE, Extra.EARRINGS), dress = true),
            f("denim-shorts", "Summer shorts", Top.CROP, Sleeve.SHORT, Neck.ROUND, CROP, Bottom.SHORTS, Outer.NONE, "#FFE66D", "#4A6FA5", "#000000", "#FF6B6B", "#FFFFFF", shoes = Shoes.SNEAKERS, extras = setOf(Extra.SUNGLASSES)),
            f("winter-coat", "Winter coat", Top.TURTLENECK, Sleeve.LONG, Neck.HIGH, HIP, Bottom.JEANS, Outer.COAT, "#F2E8DC", "#2B2D42", "#8D6E63", "#D90429", "#3E2723", shoes = Shoes.BOOTS, extras = setOf(Extra.SCARF, Extra.BEANIE)),
            f("trench", "Trench coat", Top.BLOUSE, Sleeve.LONG, Neck.V, HIP, Bottom.TROUSERS, Outer.COAT, "#FFFFFF", "#22223B", "#C8B08A", "#4A4E69", "#3C2F2F", shoes = Shoes.BOOTS, extras = setOf(Extra.BELT)),
            f("leather", "Rock leather jacket", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.LEATHER, "#222222", "#1A1A1A", "#111111", "#E63946", "#111111", shoes = Shoes.BOOTS, extras = setOf(Extra.SUNGLASSES)),
            f("track", "Track suit", Top.HOODIE, Sleeve.LONG, Neck.HIGH, HIP, Bottom.JOGGERS, Outer.NONE, "#E63946", "#E63946", "#000000", "#FFFFFF", "#FFFFFF", Pattern.STRIPES, Shoes.SNEAKERS),
            f("navy-formal", "Business formal", Top.BLOUSE, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#EEF2F7", "#14213D", "#14213D", "#FCA311", "#14213D", shoes = Shoes.HEELS, extras = setOf(Extra.NECKLACE, Extra.WATCH)),
            f("scientist", "Scientist", Top.TURTLENECK, Sleeve.LONG, Neck.HIGH, HIP, Bottom.TROUSERS, Outer.LAB_COAT, "#3D405B", "#3D405B", "#FFFFFF", "#81B29A", "#2B2B2B", shoes = Shoes.FLATS, extras = setOf(Extra.GLASSES, Extra.BADGE)),
            f("teacher", "Teacher", Top.BLOUSE, Sleeve.ELBOW, Neck.ROUND, HIP, Bottom.LONG_SKIRT, Outer.CARDIGAN, "#FAF3DD", "#5E6472", "#AED9E0", "#5E6472", "#5E6472", shoes = Shoes.FLATS, extras = setOf(Extra.GLASSES, Extra.WATCH)),
            f("graduation", "Graduation", Top.BLOUSE, Sleeve.LONG, Neck.V, KNEE, Bottom.SKIRT, Outer.ROBE, "#FFFFFF", "#111111", "#111111", "#E0B84A", "#111111", shoes = Shoes.HEELS, extras = setOf(Extra.GRAD_CAP)),
            f("hero", "Superhero", Top.SPORTS, Sleeve.LONG, Neck.HIGH, HIP, Bottom.LEGGINGS, Outer.CAPE, "#1E3A8A", "#1E3A8A", "#C1121F", "#FFD60A", "#C1121F", shoes = Shoes.BOOTS, extras = setOf(Extra.BELT)),
            f("pajamas", "Cosy pyjamas", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.JOGGERS, Outer.NONE, "#B8E0F6", "#B8E0F6", "#000000", "#FFFFFF", "#F4A6B8", Pattern.DOTS, Shoes.FLATS),
            f("beach", "Beach day", Top.TANK, Sleeve.NONE, Neck.SQUARE, CROP, Bottom.SKIRT, Outer.NONE, "#FFB4A2", "#6D9DC5", "#000000", "#FFFFFF", "#E5989B", Pattern.FLORAL, Shoes.SANDALS, setOf(Extra.SUNGLASSES, Extra.HAT)),
            f("tennis", "Tennis whites", Top.POLO, Sleeve.SHORT, Neck.COLLAR, WAIST, Bottom.SKIRT, Outer.NONE, "#FFFFFF", "#FFFFFF", "#000000", "#2A9D8F", "#FFFFFF", shoes = Shoes.SNEAKERS, extras = setOf(Extra.CAP, Extra.WATCH)),
            f("artist", "Artist", Top.TEE, Sleeve.LONG, Neck.V, HIP, Bottom.JEANS, Outer.APRON, "#F7EDE2", "#495057", "#84A59D", "#F28482", "#F7EDE2", Pattern.STRIPES, Shoes.SNEAKERS, setOf(Extra.BEANIE)),
            f("wrap-robe", "Silk wrap dress", Top.BLOUSE, Sleeve.ELBOW, Neck.V, WAIST, Bottom.LONG_SKIRT, Outer.NONE, "#2A9D8F", "#2A9D8F", "#000000", "#E9C46A", "#2A9D8F", Pattern.FLORAL, Shoes.HEELS, setOf(Extra.BELT, Extra.EARRINGS), dress = true),
            f("pastel", "Pastel day dress", Top.BLOUSE, Sleeve.SHORT, Neck.ROUND, WAIST, Bottom.SKIRT, Outer.NONE, "#CDB4DB", "#CDB4DB", "#000000", "#FFC8DD", "#FFAFCC", Pattern.DOTS, Shoes.FLATS, dress = true),
            f("biker", "Biker", Top.TANK, Sleeve.NONE, Neck.ROUND, HIP, Bottom.LEGGINGS, Outer.LEATHER, "#3A3A3A", "#111111", "#1A1A1A", "#FF9F1C", "#111111", shoes = Shoes.BOOTS, extras = setOf(Extra.SUNGLASSES)),
            f("streetwear", "Streetwear", Top.HOODIE, Sleeve.LONG, Neck.ROUND, HIP, Bottom.JOGGERS, Outer.NONE, "#111111", "#6C757D", "#000000", "#F72585", "#FFFFFF", shoes = Shoes.SNEAKERS, extras = setOf(Extra.CAP, Extra.HEADPHONES)),
            f("garden-party", "Garden party", Top.BLOUSE, Sleeve.NONE, Neck.V, WAIST, Bottom.SKIRT, Outer.NONE, "#FFE5EC", "#FB6F92", "#000000", "#FFFFFF", "#FB6F92", Pattern.FLORAL, Shoes.HEELS, setOf(Extra.HAT, Extra.EARRINGS)),
        )
    }

    val MALE: List<Outfit> by lazy {
        listOf(
            m("tee-jeans", "Tee & jeans", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.NONE, "#2F3E46", "#3B5B8C", "#000000", "#FFFFFF", "#FFFFFF"),
            m("polo-chinos", "Polo & chinos", Top.POLO, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.NONE, "#1D3557", "#D4B996", "#000000", "#FFFFFF", "#6B4423", shoes = Shoes.FORMAL, extras = setOf(Extra.WATCH)),
            m("shirt-tie", "Shirt & tie", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.NONE, "#DCE8F5", "#2B2D42", "#000000", "#9D0208", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE, Extra.BELT, Extra.WATCH)),
            m("navy-suit", "Navy suit", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#FFFFFF", "#1B263B", "#1B263B", "#C9A227", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE, Extra.POCKET_SQUARE)),
            m("charcoal-suit", "Charcoal suit", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#EAF4F4", "#3A3A3A", "#3A3A3A", "#5E60CE", "#111111", Pattern.STRIPES, Shoes.FORMAL, setOf(Extra.TIE)),
            m("tuxedo", "Tuxedo", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#FFFFFF", "#0D0D0D", "#0D0D0D", "#0D0D0D", "#0D0D0D", shoes = Shoes.FORMAL, extras = setOf(Extra.BOW_TIE, Extra.POCKET_SQUARE)),
            m("smart-casual", "Smart casual blazer", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.BLAZER, "#FFFFFF", "#2C3E50", "#8D6E63", "#FFFFFF", "#6B4423", shoes = Shoes.FORMAL, extras = setOf(Extra.WATCH)),
            m("hoodie", "Hoodie", Top.HOODIE, Sleeve.LONG, Neck.ROUND, HIP, Bottom.JOGGERS, Outer.NONE, "#4A5759", "#22223B", "#000000", "#F2CC8F", "#FFFFFF"),
            m("track", "Track suit", Top.HOODIE, Sleeve.LONG, Neck.HIGH, HIP, Bottom.JOGGERS, Outer.NONE, "#0B3D91", "#0B3D91", "#000000", "#FFFFFF", "#FFFFFF", Pattern.STRIPES),
            m("gym", "Gym wear", Top.TANK, Sleeve.NONE, Neck.ROUND, HIP, Bottom.SHORTS, Outer.NONE, "#212529", "#343A40", "#000000", "#FF6B35", "#FF6B35", extras = setOf(Extra.WATCH, Extra.HEADPHONES)),
            m("cricket", "Cricket whites", Top.POLO, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.NONE, "#FAFAFA", "#FAFAFA", "#000000", "#1D3557", "#FAFAFA", extras = setOf(Extra.CAP)),
            m("football", "Football kit", Top.TEE, Sleeve.SHORT, Neck.V, HIP, Bottom.SHORTS, Outer.NONE, "#D00000", "#FFFFFF", "#000000", "#FFBA08", "#111111", Pattern.STRIPES),
            m("kurta-white", "White kurta pyjama", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, KNEE, Bottom.CHURIDAR, Outer.NONE, "#FAF7F0", "#FAF7F0", "#000000", "#C9A227", "#C9A227", shoes = Shoes.JUTTI),
            m("kurta-festive", "Festive kurta", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, KNEE, Bottom.CHURIDAR, Outer.NONE, "#E85D04", "#FFF3E0", "#000000", "#FFBA08", "#FFBA08", Pattern.EMBROIDERY, Shoes.JUTTI),
            m("sherwani", "Groom's sherwani", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, KNEE, Bottom.CHURIDAR, Outer.SHERWANI, "#F3E5AB", "#F8F1E1", "#E9D8A6", "#9B2226", "#C9A227", Pattern.SEQUIN, Shoes.JUTTI, setOf(Extra.SAFA, Extra.NECKLACE)),
            m("sherwani-maroon", "Maroon sherwani", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, KNEE, Bottom.CHURIDAR, Outer.SHERWANI, "#6A040F", "#F8F1E1", "#6A040F", "#E9C46A", "#E9C46A", Pattern.EMBROIDERY, Shoes.JUTTI, setOf(Extra.POCKET_SQUARE)),
            m("nehru", "Nehru jacket set", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, KNEE, Bottom.CHURIDAR, Outer.NEHRU, "#FFFFFF", "#FFFFFF", "#264653", "#E9C46A", "#6B4423", shoes = Shoes.JUTTI),
            m("dhoti", "Dhoti kurta", Top.KURTA, Sleeve.LONG, Neck.MANDARIN, THIGH, Bottom.DHOTI, Outer.SHAWL, "#FFF8E7", "#FFFDF5", "#C9A227", "#C9A227", "#8B5A2B", Pattern.BORDER, Shoes.SANDALS),
            m("pathani", "Pathani suit", Top.KURTA, Sleeve.LONG, Neck.COLLAR, KNEE, Bottom.TROUSERS, Outer.NONE, "#2D3142", "#2D3142", "#000000", "#BFC0C0", "#4F3B2B", shoes = Shoes.SANDALS),
            m("bandhgala", "Bandhgala", Top.SHIRT, Sleeve.LONG, Neck.MANDARIN, HIP, Bottom.TROUSERS, Outer.NEHRU, "#F5F5F5", "#1B263B", "#1B263B", "#C9A227", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.POCKET_SQUARE)),
            m("doctor", "Doctor", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.LAB_COAT, "#BFD7EA", "#2E3A59", "#FFFFFF", "#4C9F70", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE, Extra.STETHOSCOPE, Extra.BADGE)),
            m("scrubs", "Medical scrubs", Top.SCRUBS, Sleeve.SHORT, Neck.V, HIP, Bottom.JOGGERS, Outer.NONE, "#1D4E89", "#1D4E89", "#000000", "#FFFFFF", "#FFFFFF", extras = setOf(Extra.BADGE, Extra.STETHOSCOPE)),
            m("chef", "Chef", Top.SHIRT, Sleeve.LONG, Neck.MANDARIN, HIP, Bottom.TROUSERS, Outer.APRON, "#FFFFFF", "#2B2B2B", "#F5F5F5", "#D62828", "#111111", Pattern.CHECKS, Shoes.FORMAL, setOf(Extra.CHEF_HAT)),
            m("pilot", "Pilot", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#FFFFFF", "#1D2D50", "#1D2D50", "#E0B84A", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE, Extra.BADGE, Extra.HAT)),
            m("officer", "Officer uniform", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.NONE, "#5C6B73", "#253237", "#000000", "#E0B84A", "#111111", shoes = Shoes.BOOTS, extras = setOf(Extra.BELT, Extra.BADGE, Extra.CAP)),
            m("school", "School uniform", Top.SHIRT, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.NONE, "#FFFFFF", "#24385C", "#000000", "#B22234", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE, Extra.BELT)),
            m("college", "College casual", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.JEANS, Outer.NONE, "#2A6F97", "#1B263B", "#000000", "#FFFFFF", "#ECECEC", Pattern.CHECKS, extras = setOf(Extra.WATCH)),
            m("leather", "Leather jacket", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.LEATHER, "#FFFFFF", "#1A1A1A", "#141414", "#E63946", "#111111", shoes = Shoes.BOOTS, extras = setOf(Extra.SUNGLASSES)),
            m("denim", "Denim jacket", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.TROUSERS, Outer.DENIM, "#F1FAEE", "#2B2D42", "#5F86B8", "#F2C14E", "#E9E4DA"),
            m("winter", "Winter coat", Top.TURTLENECK, Sleeve.LONG, Neck.HIGH, HIP, Bottom.JEANS, Outer.COAT, "#2B2D42", "#2B2D42", "#5D4037", "#D90429", "#3E2723", shoes = Shoes.BOOTS, extras = setOf(Extra.SCARF, Extra.BEANIE)),
            m("trench", "Trench coat", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.COAT, "#FFFFFF", "#22223B", "#C8B08A", "#4A4E69", "#3C2F2F", shoes = Shoes.FORMAL, extras = setOf(Extra.TIE)),
            m("sweater", "Knit sweater", Top.SWEATER, Sleeve.LONG, Neck.V, HIP, Bottom.TROUSERS, Outer.NONE, "#6A994E", "#3D405B", "#000000", "#FFFFFF", "#6B4423", Pattern.STRIPES, Shoes.FORMAL),
            m("cardigan", "Cardigan", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.TROUSERS, Outer.CARDIGAN, "#FFFFFF", "#495057", "#A98467", "#FFFFFF", "#6B4423", shoes = Shoes.FORMAL, extras = setOf(Extra.GLASSES)),
            m("flannel", "Flannel shirt", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.JEANS, Outer.NONE, "#B22222", "#2F3E46", "#000000", "#111111", "#5D4037", Pattern.CHECKS, Shoes.BOOTS),
            m("hawaiian", "Holiday shirt", Top.SHIRT, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.SHORTS, Outer.NONE, "#2EC4B6", "#F1E3C6", "#000000", "#FF9F1C", "#C9A27E", Pattern.FLORAL, Shoes.SANDALS, setOf(Extra.SUNGLASSES)),
            m("beach", "Beach shorts", Top.TANK, Sleeve.NONE, Neck.ROUND, HIP, Bottom.SHORTS, Outer.NONE, "#FFFFFF", "#0077B6", "#000000", "#FFD166", "#C9A27E", Pattern.STRIPES, Shoes.SANDALS, setOf(Extra.CAP, Extra.SUNGLASSES)),
            m("pajamas", "Cosy pyjamas", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.JOGGERS, Outer.NONE, "#A2D2FF", "#A2D2FF", "#000000", "#FFFFFF", "#3D405B", Pattern.CHECKS, Shoes.SANDALS),
            m("graduation", "Graduation", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.ROBE, "#FFFFFF", "#111111", "#111111", "#E0B84A", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.GRAD_CAP, Extra.TIE)),
            m("hero", "Superhero", Top.SPORTS, Sleeve.LONG, Neck.HIGH, HIP, Bottom.LEGGINGS, Outer.CAPE, "#C1121F", "#1E3A8A", "#1E3A8A", "#FFD60A", "#1E3A8A", shoes = Shoes.BOOTS, extras = setOf(Extra.BELT)),
            m("biker", "Biker", Top.TEE, Sleeve.SHORT, Neck.ROUND, HIP, Bottom.JEANS, Outer.LEATHER, "#3A3A3A", "#111111", "#1A1A1A", "#FF9F1C", "#111111", shoes = Shoes.BOOTS, extras = setOf(Extra.SUNGLASSES, Extra.BELT)),
            m("streetwear", "Streetwear", Top.HOODIE, Sleeve.LONG, Neck.ROUND, HIP, Bottom.JOGGERS, Outer.NONE, "#F72585", "#111111", "#000000", "#FFFFFF", "#FFFFFF", shoes = Shoes.SNEAKERS, extras = setOf(Extra.CAP, Extra.HEADPHONES)),
            m("golf", "Golf", Top.POLO, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.VEST, "#FFFFFF", "#CAD2C5", "#52796F", "#2F3E46", "#FFFFFF", shoes = Shoes.FORMAL, extras = setOf(Extra.CAP, Extra.BELT)),
            m("waiter", "Waistcoat", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.VEST, "#FFFFFF", "#111111", "#2B2B2B", "#9D0208", "#111111", shoes = Shoes.FORMAL, extras = setOf(Extra.BOW_TIE)),
            m("tech", "Turtleneck tech", Top.TURTLENECK, Sleeve.LONG, Neck.HIGH, HIP, Bottom.JEANS, Outer.NONE, "#111111", "#2C3E50", "#000000", "#FFFFFF", "#E5E5E5", shoes = Shoes.SNEAKERS, extras = setOf(Extra.GLASSES)),
            m("safari", "Safari", Top.SHIRT, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.SHORTS, Outer.VEST, "#C2B280", "#8B7D5B", "#6B705C", "#3A5A40", "#5D4037", shoes = Shoes.BOOTS, extras = setOf(Extra.HAT, Extra.BELT)),
            m("hiker", "Mountain hiker", Top.TEE, Sleeve.LONG, Neck.ROUND, HIP, Bottom.JOGGERS, Outer.JACKET, "#F4A261", "#495057", "#2A9D8F", "#E76F51", "#5D4037", shoes = Shoes.BOOTS, extras = setOf(Extra.BEANIE)),
            m("musician", "Musician", Top.TEE, Sleeve.SHORT, Neck.V, HIP, Bottom.JEANS, Outer.JACKET, "#6D597A", "#1A1A1A", "#355070", "#EAAC8B", "#111111", shoes = Shoes.BOOTS, extras = setOf(Extra.NECKLACE, Extra.HEADPHONES)),
            m("groom-white", "Ivory wedding suit", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.BLAZER, "#FFFFFF", "#F8F4E3", "#F8F4E3", "#B08968", "#6B4423", shoes = Shoes.FORMAL, extras = setOf(Extra.BOW_TIE, Extra.POCKET_SQUARE)),
            m("party", "Party shirt", Top.SHIRT, Sleeve.LONG, Neck.COLLAR, HIP, Bottom.TROUSERS, Outer.NONE, "#5A189A", "#111111", "#000000", "#E0AAFF", "#111111", Pattern.SEQUIN, Shoes.FORMAL, setOf(Extra.WATCH)),
            m("casual-check", "Weekend checks", Top.SHIRT, Sleeve.SHORT, Neck.COLLAR, HIP, Bottom.SHORTS, Outer.NONE, "#F4D35E", "#264653", "#000000", "#EE964B", "#FFFFFF", Pattern.CHECKS, Shoes.SNEAKERS, setOf(Extra.SUNGLASSES)),
        )
    }

    fun forGender(g: Gender): List<Outfit> = if (g == Gender.FEMALE) FEMALE else MALE

    fun byId(id: String?, gender: Gender): Outfit = forGender(gender).firstOrNull { it.id == id } ?: forGender(gender).first()
}

/** Hair, beard and skin choices for a body. */
enum class HairStyle(val label: String, val gender: Gender?) {
    LONG_STRAIGHT("Long straight", Gender.FEMALE),
    LONG_WAVY("Long waves", Gender.FEMALE),
    PONYTAIL("Ponytail", Gender.FEMALE),
    BUN("Bun", Gender.FEMALE),
    BRAID("Braid", Gender.FEMALE),
    BOB("Bob", Gender.FEMALE),
    PIXIE("Pixie", null),
    CURLY("Curly", null),
    SHORT("Short", Gender.MALE),
    SIDE_PART("Side part", Gender.MALE),
    QUIFF("Quiff", Gender.MALE),
    BUZZ("Buzz cut", Gender.MALE),
    LONG_MALE("Shoulder length", Gender.MALE),
    BALD("Bald", Gender.MALE),
}

enum class Beard(val label: String) { NONE("Clean shaven"), STUBBLE("Stubble"), SHORT("Short beard"), FULL("Full beard"), MUSTACHE("Moustache") }

/** Everything that makes up a full-body avatar. */
data class BodySpec(
    val gender: Gender,
    val outfitId: String,
    val hair: HairStyle,
    val hairColor: Int,
    val skin: Int,
    val eyeColor: Int,
    val beard: Beard = Beard.NONE,
    /** True: skin colour is taken from the uploaded face so the body matches it. */
    val skinFromFace: Boolean = false,
    /** True: shown as a photo-realistic person (see [RealBody]) instead of drawn. */
    val realistic: Boolean = true,
) {
    val outfit: Outfit get() = Outfits.byId(outfitId, gender)

    fun toJson(): JSONObject = JSONObject()
        .put("gender", gender.name)
        .put("outfit", outfitId)
        .put("hair", hair.name)
        .put("hairColor", hairColor)
        .put("skin", skin)
        .put("eyes", eyeColor)
        .put("beard", beard.name)
        .put("skinFromFace", skinFromFace)
        .put("realistic", realistic)
        .put("v", 2)

    companion object {
        val SKINS = listOf("#FBE3D3", "#F1C9A5", "#E0AC84", "#C68B62", "#A86F4C", "#8D5A3B", "#6B4128", "#4A2C1A")
            .map { android.graphics.Color.parseColor(it) }
        val HAIR_COLORS = listOf("#141012", "#3B2416", "#6A4329", "#A0703F", "#D9B278", "#B5462E", "#8E8E93", "#E8E4DC", "#5B2A86", "#1F6F8B")
            .map { android.graphics.Color.parseColor(it) }
        val EYE_COLORS = listOf("#3D2314", "#6B4423", "#2E6B4F", "#3B6EA5", "#7A7F87")
            .map { android.graphics.Color.parseColor(it) }

        val FEMALE_DEFAULT = BodySpec(Gender.FEMALE, "f-saree-blue", HairStyle.LONG_WAVY, HAIR_COLORS[0], SKINS[2], EYE_COLORS[0])
        val MALE_DEFAULT = BodySpec(Gender.MALE, "m-smart-casual", HairStyle.SIDE_PART, HAIR_COLORS[0], SKINS[3], EYE_COLORS[0], Beard.STUBBLE)

        fun defaultFor(g: Gender) = if (g == Gender.FEMALE) FEMALE_DEFAULT else MALE_DEFAULT

        fun fromJson(j: JSONObject?): BodySpec? {
            if (j == null) return null
            return try {
                val g = Gender.valueOf(j.getString("gender"))
                val d = defaultFor(g)
                BodySpec(
                    gender = g,
                    outfitId = j.optString("outfit", d.outfitId),
                    hair = runCatching { HairStyle.valueOf(j.getString("hair")) }.getOrDefault(d.hair),
                    hairColor = j.optInt("hairColor", d.hairColor),
                    skin = j.optInt("skin", d.skin),
                    eyeColor = j.optInt("eyes", d.eyeColor),
                    beard = runCatching { Beard.valueOf(j.getString("beard")) }.getOrDefault(Beard.NONE),
                    skinFromFace = j.optBoolean("skinFromFace", false),
                    // Looks saved before realistic bodies existed become realistic.
                    realistic = if (j.optInt("v", 1) < 2) true else j.optBoolean("realistic", true),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
