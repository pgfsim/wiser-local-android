package dev.wiserlocal.app

/** A device's Zigbee address (16 hex digits) and install code (36 hex digits: 32 plus a 4-digit check). */
data class InstallCode(val address: String, val code: String)

/**
 * Reads the pairing QR code printed on devices such as radiator valves, in the same
 * layouts the official app accepts:
 *  - `<address>|<code>`
 *  - `Z$A:<address>$I:<code>`, optionally followed by `$M:<model>`
 *  - `EUI-64: xxxx xxxx xxxx xxxx Install Code: xxxx … xxxx` (printed groups of four)
 *  - `<address> <code>`
 */
object InstallCodeQr {

    private const val HEX = "[0-9A-Fa-f]"
    private val LAYOUTS = listOf(
        Regex("($HEX{16})\\|($HEX{36})"),
        Regex("Z\\\$A:($HEX{16})\\\$I:($HEX{36})"),
        Regex("EUI-64:\\s*((?:$HEX{4} ){3}$HEX{4})\\s*Install Code:\\s*((?:$HEX{4} ){8}$HEX{4})", RegexOption.IGNORE_CASE),
        Regex("($HEX{16}) ($HEX{36})"),
    )

    fun parse(text: String): InstallCode? {
        val input = text.trim()
        for (layout in LAYOUTS) {
            val m = layout.find(input) ?: continue
            return InstallCode(
                m.groupValues[1].replace(" ", "").uppercase(),
                m.groupValues[2].replace(" ", "").uppercase(),
            )
        }
        return null
    }

    /** True for a 16-hex-digit address and a 36-hex-digit code (spaces and dashes ignored). */
    fun isValid(address: String, code: String): Boolean =
        clean(address).matches(Regex("$HEX{16}")) && clean(code).matches(Regex("$HEX{36}"))

    fun clean(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase()
}
