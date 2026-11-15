// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

// Names, category membership and ordering follow Fedora Media Writer metadata.json.
enum class EditionGroup(val label: String) {
    OFFICIAL("Oficjalne edycje"), ATOMIC("Atomic Desktops"), SPINS("Spins"), LABS("Labs")
}
data class Edition(val id: String, val name: String, val group: EditionGroup, val aliases: Set<String>)
object EditionCatalog {
    private val definitions = listOf(
        Edition("workstation", "Fedora Workstation", EditionGroup.OFFICIAL, setOf("workstation")),
        Edition("kde", "Fedora KDE Plasma Desktop", EditionGroup.OFFICIAL, setOf("kde")),
        Edition("iot", "Fedora IoT", EditionGroup.OFFICIAL, setOf("iot", "iot_simplified_provisioner")),
        Edition("server", "Fedora Server", EditionGroup.OFFICIAL, setOf("server")),
        Edition("everything", "Fedora Everything", EditionGroup.OFFICIAL, setOf("everything")),
        Edition("silverblue", "Fedora Silverblue", EditionGroup.ATOMIC, setOf("silverblue")),
        Edition("kinoite", "Fedora Kinoite", EditionGroup.ATOMIC, setOf("kinoite")),
        Edition("onyx", "Fedora Budgie Atomic", EditionGroup.ATOMIC, setOf("onyx", "budgie-atomic")),
        Edition("sericea", "Fedora Sway Atomic", EditionGroup.ATOMIC, setOf("sericea", "sway-atomic")),
        Edition("cosmic-atomic", "Fedora COSMIC Atomic", EditionGroup.ATOMIC, setOf("cosmic-atomic")),
        Edition("kde_mobile", "Fedora KDE Plasma Mobile", EditionGroup.SPINS, setOf("kde_mobile")),
        Edition("xfce", "Fedora Xfce Desktop", EditionGroup.SPINS, setOf("xfce")),
        Edition("lxqt", "Fedora LXQt Desktop", EditionGroup.SPINS, setOf("lxqt")),
        Edition("cinnamon", "Fedora Cinnamon Desktop", EditionGroup.SPINS, setOf("cinnamon")),
        Edition("lxde", "Fedora LXDE Desktop", EditionGroup.SPINS, setOf("lxde")),
        Edition("soas", "Fedora SoaS Desktop", EditionGroup.SPINS, setOf("soas")),
        Edition("i3", "Fedora i3 Desktop", EditionGroup.SPINS, setOf("i3")),
        Edition("mate", "Fedora MATE-Compiz Desktop", EditionGroup.SPINS, setOf("mate")),
        Edition("sway", "Fedora Sway Desktop", EditionGroup.SPINS, setOf("sway")),
        Edition("budgie", "Fedora Budgie Desktop", EditionGroup.SPINS, setOf("budgie")),
        Edition("cosmic", "Fedora COSMIC Desktop", EditionGroup.SPINS, setOf("cosmic")),
        Edition("miraclewm", "Fedora MiracleWM Desktop", EditionGroup.SPINS, setOf("miraclewm")),
        Edition("astronomy", "Fedora Astronomy", EditionGroup.LABS, setOf("astronomy", "astronomy_kde")),
        Edition("design_suite", "Fedora Design Suite", EditionGroup.LABS, setOf("design_suite")),
        Edition("jam", "Fedora Jam", EditionGroup.LABS, setOf("jam", "jam_kde")),
        Edition("games", "Fedora Games", EditionGroup.LABS, setOf("games")),
        Edition("python", "Fedora Python Classroom", EditionGroup.LABS, setOf("python", "python_classroom")),
        Edition("robotics", "Fedora Robotics Suite", EditionGroup.LABS, setOf("robotics")),
        Edition("scientific", "Fedora Scientific", EditionGroup.LABS, setOf("scientific", "scientific_kde")),
        Edition("security", "Fedora Security Lab", EditionGroup.LABS, setOf("security")),
    )
    fun edition(release: Release): Edition {
        val key = release.edition.lowercase(java.util.Locale.ROOT)
        val variant = release.variant.lowercase(java.util.Locale.ROOT)
        return definitions.firstOrNull { key in it.aliases }
            ?: definitions.firstOrNull { variant in it.aliases }
            ?: Edition(key, "Fedora ${release.edition}", when(variant) {
                "spins" -> EditionGroup.SPINS
                "labs" -> EditionGroup.LABS
                else -> EditionGroup.OFFICIAL
            }, setOf(key))
    }
    // The simple two-field picker targets Intel/AMD PCs; never use the phone's ABI.
    fun choices(releases: List<Release>): List<Edition> = releases.filter { it.arch == "x86_64" }
        .map(::edition).distinctBy { it.id }
        .sortedWith(compareBy<Edition> { it.group.ordinal }.thenBy {
            definitions.indexOfFirst { definition -> definition.id == it.id }.takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it.name })
    fun versions(releases: List<Release>, editionId: String): List<Release> = releases
        .filter { it.arch == "x86_64" && edition(it).id == editionId }
        // Prefer standard install/live ISO over the alternate IoT provisioner.
        .sortedBy { if (it.edition.contains("Simplified", ignoreCase=true)) 1 else 0 }
        .distinctBy { it.version }
        .sortedByDescending { it.version.toIntOrNull() ?: 0 }
}
