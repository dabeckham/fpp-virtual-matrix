package app.fppvm.tv.panel

import org.json.JSONArray
import org.json.JSONObject

/**
 * The appearance of a real LED product.
 *
 * Geometry alone does not make a panel look real. The cues that actually sell it are the size of
 * the dark gap, the colour of whatever is behind the emitters, and — on outdoor cabinets — the
 * shade above each row. So a profile is a record of appearance, not three numbers.
 *
 * Emitter sizes come from the SMD package part number wherever one exists. The naming convention
 * is the package's physical size in tenths of a millimetre (SMD3535 is 3.5 x 3.5 mm), which makes
 * every figure here checkable against a datasheet instead of resting on someone's recollection.
 */
data class LedProfile(
    val id: String,
    val label: String,
    /** Centre-to-centre spacing, millimetres. This is the "P" number. */
    val pitchMm: Float,
    /** Visible emitting area — the package face, millimetres. */
    val emitterMm: Float,
    val shape: EmitterShape,
    /** Package part number, or "" for products that have none (bullets, retrofit bulbs). */
    val packageName: String = "",
    /** Colour of the unlit surface. 65-80% of the panel is this, so it matters. */
    val substrate: Int = SUBSTRATE_BLACK_MASK,
    /**
     * Depth of the shade above each row, as a percentage of the cell. Outdoor cabinets have a
     * louvre to keep sun off the emitters, and the shadow it casts is the single most recognisable
     * feature of an outdoor panel. 0 for indoor and for anything that is not a panel.
     */
    val louvrePercent: Int = 0,
    /** Default bloom for this product; still adjustable afterwards. */
    val bloomPercent: Int = 45,
    val builtIn: Boolean = true,
    val notes: String = ""
) {
    /** Lit fraction of a cell along one axis. */
    val fillRatio: Float get() = if (pitchMm > 0f) emitterMm / pitchMm else 1f

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("pitchMm", pitchMm.toDouble())
        put("emitterMm", emitterMm.toDouble())
        put("shape", shape.name)
        put("packageName", packageName)
        put("substrate", substrate)
        put("louvrePercent", louvrePercent)
        put("bloomPercent", bloomPercent)
        put("builtIn", builtIn)
        put("notes", notes)
    }

    companion object {
        /** Black epoxy mask, what fine-pitch indoor panels use. Not quite pure black. */
        const val SUBSTRATE_BLACK_MASK = 0xFF080808.toInt()

        /** Bare black-painted module face, typical of outdoor cabinets. */
        const val SUBSTRATE_BLACK_PANEL = 0xFF000000.toInt()

        /** Cheap module showing its PCB between emitters. */
        const val SUBSTRATE_GREY_PCB = 0xFF2A2E2A.toInt()

        /** Nothing behind the light at all — bullets on a strand, seen at night. */
        const val SUBSTRATE_NONE = 0xFF000000.toInt()

        fun fromJson(o: JSONObject): LedProfile? = try {
            LedProfile(
                id = o.getString("id"),
                label = o.optString("label", o.getString("id")),
                pitchMm = o.optDouble("pitchMm", 10.0).toFloat(),
                emitterMm = o.optDouble("emitterMm", 3.5).toFloat(),
                shape = try {
                    EmitterShape.valueOf(o.optString("shape", "SQUARE").uppercase())
                } catch (t: IllegalArgumentException) {
                    EmitterShape.SQUARE
                },
                packageName = o.optString("packageName", ""),
                substrate = o.optInt("substrate", SUBSTRATE_BLACK_MASK),
                louvrePercent = o.optInt("louvrePercent", 0),
                bloomPercent = o.optInt("bloomPercent", 45),
                builtIn = o.optBoolean("builtIn", false),
                notes = o.optString("notes", "")
            )
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * The built-in library.
 *
 * Every SMD figure is the package's own dimensions; the package is named so it can be checked.
 * Note the fill ratio *rises* as the pitch gets finer — P10 is 0.35 and P2.5 is 0.60 — which is
 * why a fine-pitch wall reads as a solid image and a P10 reads as discrete dots.
 */
object LedProfiles {

    val BUILT_IN: List<LedProfile> = listOf(
        // --- Indoor / fine pitch. Black mask, no louvre.
        LedProfile(
            "p2_5", "P2.5 indoor", 2.5f, 1.5f, EmitterShape.SQUARE, "SMD1515",
            substrate = LedProfile.SUBSTRATE_BLACK_MASK, bloomPercent = 30,
            notes = "Fill 0.60 — reads almost solid at any distance."
        ),
        LedProfile(
            "p3", "P3 indoor", 3.0f, 2.1f, EmitterShape.SQUARE, "SMD2121",
            substrate = LedProfile.SUBSTRATE_BLACK_MASK, bloomPercent = 30
        ),
        LedProfile(
            "p4", "P4", 4.0f, 2.1f, EmitterShape.SQUARE, "SMD2121",
            substrate = LedProfile.SUBSTRATE_BLACK_MASK, bloomPercent = 35
        ),
        LedProfile(
            "p5", "P5", 5.0f, 2.7f, EmitterShape.SQUARE, "SMD2727",
            substrate = LedProfile.SUBSTRATE_BLACK_MASK, bloomPercent = 40
        ),

        // --- Outdoor cabinets. Louvred, and the shade is what makes them recognisable.
        LedProfile(
            "p6", "P6 outdoor", 6.0f, 3.5f, EmitterShape.SQUARE, "SMD3535",
            substrate = LedProfile.SUBSTRATE_BLACK_PANEL, louvrePercent = 12, bloomPercent = 45
        ),
        LedProfile(
            "p8", "P8 outdoor", 8.0f, 3.5f, EmitterShape.SQUARE, "SMD3535",
            substrate = LedProfile.SUBSTRATE_BLACK_PANEL, louvrePercent = 14, bloomPercent = 45
        ),
        LedProfile(
            "p10", "P10 outdoor SMD", 10.0f, 3.5f, EmitterShape.SQUARE, "SMD3535",
            substrate = LedProfile.SUBSTRATE_BLACK_PANEL, louvrePercent = 16, bloomPercent = 50,
            notes = "Fill 0.35 — clearly separate dots. The classic outdoor module."
        ),
        LedProfile(
            "p10_dip", "P10 outdoor DIP (round)", 10.0f, 3.8f, EmitterShape.ROUND, "DIP346",
            substrate = LedProfile.SUBSTRATE_BLACK_PANEL, louvrePercent = 16, bloomPercent = 55,
            notes = "Older through-hole outdoor module — round lenses rather than square packages."
        ),

        // --- Bullet / seed pixels on a strand. No substrate to speak of at night.
        LedProfile(
            "bullet_12", "Bullet 12mm @ 12mm", 12.0f, 12.0f, EmitterShape.ROUND,
            substrate = LedProfile.SUBSTRATE_NONE, bloomPercent = 55,
            notes = "Bulbs touching — the densest a 12mm bullet can be strung."
        ),
        LedProfile(
            "bullet_19", "Bullet 12mm @ 19mm", 19.05f, 12.0f, EmitterShape.ROUND,
            substrate = LedProfile.SUBSTRATE_NONE, bloomPercent = 60
        ),
        LedProfile(
            "bullet_25", "Bullet 12mm @ 1in", 25.4f, 12.0f, EmitterShape.ROUND,
            substrate = LedProfile.SUBSTRATE_NONE, bloomPercent = 65,
            notes = "12mm bulbs on a 1 inch pitch — 18% lit."
        ),
        LedProfile(
            "bullet_38", "Bullet 12mm @ 1.5in", 38.1f, 12.0f, EmitterShape.ROUND,
            substrate = LedProfile.SUBSTRATE_NONE, bloomPercent = 70
        ),
        LedProfile(
            "bullet_50", "Bullet 12mm @ 2in", 50.0f, 12.0f, EmitterShape.ROUND,
            substrate = LedProfile.SUBSTRATE_NONE, bloomPercent = 75,
            notes = "Widely spaced — individual bulbs, lots of darkness."
        )
    )

    val DEFAULT: LedProfile = BUILT_IN.first { it.id == "bullet_25" }

    /**
     * Where saved profiles come from.
     *
     * They live in SharedPreferences, which this object deliberately cannot reach — keeping the
     * library pure is what lets the solver and the config be unit-tested without Android. The app
     * installs a lookup at start-up; with none installed only the built-ins resolve, which is
     * exactly what a test wants.
     *
     * This has to exist because [byId] is the single point every caller goes through. While it
     * searched only [BUILT_IN], selecting one of your own saved profiles resolved to null and
     * silently did nothing, and [MatrixConfig.profileEdited] reported every one of them as
     * modified because it could not find anything to compare against.
     */
    @Volatile
    var userProfiles: () -> List<LedProfile> = { emptyList() }

    /** Built-ins first, then anything the user saved. */
    fun all(): List<LedProfile> = BUILT_IN + userProfiles()

    fun byId(id: String): LedProfile? =
        BUILT_IN.firstOrNull { it.id == id } ?: userProfiles().firstOrNull { it.id == id }

    fun listToJson(profiles: List<LedProfile>): JSONArray =
        JSONArray().apply { profiles.forEach { put(it.toJson()) } }

    fun listFromJson(text: String): List<LedProfile> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { LedProfile.fromJson(arr.getJSONObject(it)) }
    } catch (t: Throwable) {
        emptyList()
    }
}
