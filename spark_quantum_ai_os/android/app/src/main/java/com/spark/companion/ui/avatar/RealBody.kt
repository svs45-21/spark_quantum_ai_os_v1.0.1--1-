package com.spark.companion.ui.avatar

import android.graphics.Bitmap
import com.spark.companion.brain.ImageGen
import com.spark.companion.brain.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo-realistic full-body avatars. An image model draws a real-looking
 * person, head to toe, in the chosen outfit (with your face when the model can
 * use a photo). The backdrop is removed so they stand in open space, and the
 * face is found so it can blink, talk and show feelings.
 */
object RealBody {

    /** A finished realistic body: the cut-out picture and where its face is (fractions of the picture). */
    class Result(val bitmap: Bitmap, val face: FaceLandmarks)

    /** Backdrop colour asked for: plain mid grey separates well from skin, white and black clothes. */
    private const val BACKDROP = "plain seamless medium grey studio backdrop (#9EA3A8) filling the whole background"

    fun prompt(spec: BodySpec, likenessFromPhoto: Boolean): String {
        val person = if (spec.gender == Gender.FEMALE) "young adult woman" else "young adult man"
        val look = buildString {
            if (!likenessFromPhoto) {
                append(" with ").append(skinWord(spec.skin)).append(" skin and ")
                append(colourName(spec.hairColor)).append(" ").append(hairWords(spec.hair)).append(" hair")
                if (spec.gender == Gender.MALE) when (spec.beard) {
                    Beard.STUBBLE -> append(", light stubble")
                    Beard.SHORT -> append(", a short neat beard")
                    Beard.FULL -> append(", a full beard")
                    Beard.MUSTACHE -> append(", a moustache")
                    Beard.NONE -> append(", clean shaven")
                }
            }
        }
        val who = if (likenessFromPhoto) "the person in the attached photo (keep their exact face, skin tone and hair)" else "a $person$look"
        return "Photorealistic full-length fashion photograph of $who, standing upright and facing the camera, " +
            "the whole body visible from the top of the head to the feet with some space above and below, " +
            "arms relaxed at the sides, shoulders level, friendly calm expression, eyes open looking at the camera, mouth closed. " +
            "Wearing ${describe(spec.outfit)}. " +
            "$BACKDROP, soft even front lighting, no strong shadows on the backdrop. " +
            "A real human being photographed with a DSLR camera: natural skin with pores and subtle imperfections, " +
            "real hair with fine individual strands and natural flyaways, realistic fabric folds and texture, " +
            "realistic human proportions, sharp focus, 85mm lens, portrait orientation. " +
            "Not a cartoon, not an illustration, not anime, not 3D render, not CGI, not a doll, not a mannequin. " +
            "No text, no logos, no watermark, no other people, no props."
    }

    /** A plain-English description of an outfit for the image model. */
    fun describe(o: Outfit): String {
        val top = colourName(o.topColor)
        val bottom = colourName(o.bottomColor)
        val outer = colourName(o.outerColor)
        val accent = colourName(o.accent)
        val pattern = when (o.pattern) {
            Pattern.PLAIN -> ""
            Pattern.STRIPES -> "striped "
            Pattern.CHECKS -> "checked "
            Pattern.DOTS -> "polka-dot "
            Pattern.FLORAL -> "floral-print "
            Pattern.SEQUIN -> "sequinned "
            Pattern.BORDER -> ""
            Pattern.EMBROIDERY -> "embroidered "
        }
        val sleeves = when (o.sleeve) {
            Sleeve.NONE -> "sleeveless "
            Sleeve.SHORT -> "short-sleeved "
            Sleeve.ELBOW -> "elbow-sleeved "
            Sleeve.LONG -> "long-sleeved "
        }
        val parts = ArrayList<String>()
        when {
            o.outer == Outer.PALLU ->
                parts += "a $bottom ${pattern}silk saree with a $accent border, draped traditionally with the pallu over the left shoulder, and a $top ${sleeves}blouse"
            o.bottom == Bottom.LEHENGA && !o.dress ->
                parts += "a $bottom ${pattern}lehenga skirt with a $accent border and a $top ${sleeves}choli blouse"
            o.dress -> {
                val kind = when (o.bottom) {
                    Bottom.LONG_SKIRT -> "floor-length gown"
                    Bottom.LEHENGA -> "flared floor-length ball gown"
                    Bottom.PENCIL -> "fitted knee-length dress"
                    else -> "knee-length dress"
                }
                parts += if (o.top == Top.KURTA) "a $top ${pattern}${sleeves}long anarkali kurta" else "a $top ${pattern}${sleeves}$kind"
            }
            else -> {
                val topName = when (o.top) {
                    Top.TEE -> "t-shirt"; Top.SHIRT -> "button-up shirt"; Top.BLOUSE -> "blouse"; Top.TANK -> "tank top"
                    Top.HOODIE -> "hoodie"; Top.SWEATER -> "knit sweater"; Top.TURTLENECK -> "turtleneck"; Top.POLO -> "polo shirt"
                    Top.KURTA -> if (o.topLength > 0.65f) "knee-length kurta" else "kurta"; Top.CROP -> "crop top"
                    Top.CHOLI -> "choli blouse"; Top.SPORTS -> "fitted sports top"; Top.SCRUBS -> "medical scrub top"
                }
                parts += "a $top $pattern${if (o.top == Top.TANK || o.top == Top.SPORTS && o.sleeve == Sleeve.NONE) "" else sleeves}$topName"
                parts += when (o.bottom) {
                    Bottom.JEANS -> "$bottom jeans"
                    Bottom.TROUSERS -> "$bottom tailored trousers"
                    Bottom.JOGGERS -> "$bottom joggers"
                    Bottom.SHORTS -> "$bottom shorts"
                    Bottom.SKIRT -> "a $bottom knee-length skirt"
                    Bottom.PENCIL -> "a $bottom pencil skirt"
                    Bottom.LONG_SKIRT -> "a $bottom long skirt"
                    Bottom.LEHENGA -> "a $bottom flared lehenga skirt"
                    Bottom.LEGGINGS -> "$bottom leggings"
                    Bottom.PALAZZO -> "$bottom wide palazzo trousers"
                    Bottom.DHOTI -> "a $bottom traditional dhoti"
                    Bottom.CHURIDAR -> "$bottom churidar pyjama"
                }
            }
        }
        when (o.outer) {
            Outer.NONE, Outer.PALLU -> Unit
            Outer.JACKET -> parts += "an open $outer zip jacket"
            Outer.DENIM -> parts += "an open $outer denim jacket"
            Outer.LEATHER -> parts += "an open $outer leather biker jacket"
            Outer.BLAZER -> parts += "an open $outer tailored blazer"
            Outer.COAT -> parts += "an open $outer long coat"
            Outer.CARDIGAN -> parts += "an open $outer cardigan"
            Outer.LAB_COAT -> parts += "an open white doctor's lab coat"
            Outer.SHERWANI -> parts += "a $outer ${pattern}knee-length sherwani with $accent buttons"
            Outer.NEHRU -> parts += "a $outer Nehru jacket"
            Outer.VEST -> parts += "a $outer waistcoat"
            Outer.DUPATTA -> parts += "a $outer dupatta draped over both shoulders"
            Outer.SHAWL -> parts += "a $outer shawl over one shoulder"
            Outer.CAPE -> parts += "a $outer superhero cape"
            Outer.APRON -> parts += "a $outer chef's apron"
            Outer.ROBE -> parts += "a $outer graduation gown with a $accent stole"
        }
        val extras = o.extras.mapNotNull {
            when (it) {
                Extra.TIE -> "a $accent tie"; Extra.BOW_TIE -> "a bow tie"; Extra.BELT -> "a leather belt"
                Extra.SCARF -> "a $accent scarf"; Extra.NECKLACE -> "a delicate gold necklace"; Extra.EARRINGS -> "gold earrings"
                Extra.BINDI -> "a small red bindi"; Extra.GLASSES -> "glasses"; Extra.SUNGLASSES -> "sunglasses"
                Extra.CAP -> "a $accent cap"; Extra.BEANIE -> "a $accent beanie"; Extra.HAT -> "a $accent hat"
                Extra.WATCH -> "a wristwatch"; Extra.HEADPHONES -> "headphones around the neck"; Extra.STETHOSCOPE -> "a stethoscope around the neck"
                Extra.BADGE -> "a name badge"; Extra.CHEF_HAT -> "a white chef's hat"; Extra.GRAD_CAP -> "a graduation cap"
                Extra.MAANG_TIKKA -> "a gold maang tikka"; Extra.BANGLES -> "gold bangles"; Extra.POCKET_SQUARE -> "a pocket square"
                Extra.SAFA -> "a festive red and orange safa turban"
            }
        }
        val shoes = when (o.shoes) {
            Shoes.SNEAKERS -> "sneakers"; Shoes.FORMAL -> "polished formal shoes"; Shoes.HEELS -> "heels"; Shoes.BOOTS -> "boots"
            Shoes.SANDALS -> "sandals"; Shoes.FLATS -> "flat shoes"; Shoes.JUTTI -> "embroidered juttis"
        }
        return (parts + extras).joinToString(", ") + ", and ${colourName(o.shoeColor)} $shoes"
    }

    private fun hairWords(h: HairStyle) = when (h) {
        HairStyle.LONG_STRAIGHT -> "long straight"; HairStyle.LONG_WAVY -> "long wavy"; HairStyle.PONYTAIL -> "ponytail"
        HairStyle.BUN -> "neat bun"; HairStyle.BRAID -> "long braided"; HairStyle.BOB -> "bob-cut"; HairStyle.PIXIE -> "pixie-cut"
        HairStyle.CURLY -> "curly"; HairStyle.SHORT -> "short"; HairStyle.SIDE_PART -> "short side-parted"; HairStyle.QUIFF -> "quiff"
        HairStyle.BUZZ -> "buzz-cut"; HairStyle.LONG_MALE -> "shoulder-length"; HairStyle.BALD -> "shaved"
    }

    private fun skinWord(c: Int): String {
        val l = luminance(c)
        return when {
            l > 0.8f -> "fair"; l > 0.7f -> "light"; l > 0.58f -> "medium wheatish"; l > 0.45f -> "tan brown"
            l > 0.33f -> "brown"; else -> "deep brown"
        }
    }

    private fun luminance(c: Int): Float =
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    private val NAMED = listOf(
        "white" to 0xFFFFFF, "ivory" to 0xF8F1E1, "cream" to 0xF3E5AB, "black" to 0x111111, "charcoal grey" to 0x3A3A3A,
        "grey" to 0x8D8D8D, "light grey" to 0xC8C8C8, "navy blue" to 0x1B263B, "royal blue" to 0x1E3A8A, "blue" to 0x2F6690,
        "sky blue" to 0xA2D2FF, "denim blue" to 0x5F86B8, "teal" to 0x2A9D8F, "turquoise" to 0x2EC4B6, "green" to 0x1B7F4C,
        "sage green" to 0x84A59D, "olive" to 0x6B705C, "red" to 0xC1121F, "crimson" to 0x9E0F2A, "maroon" to 0x6A040F,
        "pink" to 0xFF6FA8, "pastel pink" to 0xF7D9E3, "magenta" to 0xC2185B, "lavender" to 0xCDB4DB, "purple" to 0x5E2B97,
        "orange" to 0xF45D01, "saffron" to 0xE85D04, "mustard yellow" to 0xD9A441, "yellow" to 0xFFE66D, "gold" to 0xE1B84F,
        "beige" to 0xD4B996, "camel" to 0xC8B08A, "tan" to 0xC9A27E, "brown" to 0x6B4423, "dark brown" to 0x3E2723,
        "peach" to 0xFFB4A2, "coral" to 0xF28482, "mint" to 0xE6F2E6,
    )

    /** The nearest everyday colour name for an ARGB colour. */
    fun colourName(c: Int): String {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return NAMED.minByOrNull { (_, v) ->
            val dr = r - ((v shr 16) and 0xFF); val dg = g - ((v shr 8) and 0xFF); val db = b - (v and 0xFF)
            2 * dr * dr + 4 * dg * dg + 3 * db * db
        }!!.first
    }

    /**
     * Generates the body with [gen], finds the face and cuts the person out.
     * [facePhoto] is passed to generators that can keep a likeness.
     */
    @Throws(LlmException::class)
    suspend fun create(gen: ImageGen, spec: BodySpec, facePhoto: Bitmap?): Result {
        val likeness = facePhoto != null && gen.canUsePhoto
        val bytes = gen.generate(prompt(spec, likeness), if (likeness) withContext(Dispatchers.Default) { FaceTools.jpeg(facePhoto!!) } else null)
        val image = withContext(Dispatchers.Default) { FaceTools.decode(bytes) }
            ?: throw LlmException("The generated picture couldn't be opened. Try again.")
        val face = FaceTools.detect(image)
            ?: throw LlmException("The generated picture didn't show a clear face. Try again — each try gives a new picture.")
        return withContext(Dispatchers.Default) { cutOut(image, face) }
    }

    /** Removes the backdrop, crops to the person and converts the face to fractions of the crop. */
    fun cutOut(image: Bitmap, face: FaceTools.Face): Result {
        val w = image.width
        val h = image.height
        val px = IntArray(w * h)
        image.getPixels(px, 0, w, 0, 0, w, h)
        val clean = Cutout.remove(px, w, h)
        val box = Cutout.bounds(clean, w, h) ?: intArrayOf(0, 0, w, h)
        // Keep a little room around the figure; never cut through the face.
        val pad = (h * 0.015f).toInt()
        val l = (box[0] - pad).coerceAtLeast(0)
        val t = minOf((box[1] - pad).coerceAtLeast(0), (face.leftEyeY - face.eyeDistance * 2.5f).toInt().coerceAtLeast(0))
        val r = (box[2] + pad).coerceAtMost(w)
        val b = (box[3] + pad).coerceAtMost(h)
        val cw = r - l
        val ch = b - t
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        out.setPixels(clean, t * w + l, w, 0, 0, cw, ch)
        fun fx(x: Float) = (x - l) / cw
        fun fy(y: Float) = (y - t) / ch
        val lm = FaceLandmarks(
            fx(face.leftEyeX), fy(face.leftEyeY), fx(face.rightEyeX), fy(face.rightEyeY),
            fx(face.mouthLeftX), fy(face.mouthLeftY), fx(face.mouthRightX), fy(face.mouthRightY),
        )
        return Result(out, lm)
    }
}
