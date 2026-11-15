// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import org.junit.Assert.*
import org.junit.Test

class EditionCatalogTest {
    private fun release(edition: String, version: String = "45", arch: String = "x86_64", variant: String = edition) =
        Release(version,edition,arch,"https://example.org/$edition-$version-$arch.iso","a".repeat(64),1024,variant)

    @Test fun groupsAndOrdersEditionsLikeUpstream() {
        val choices=EditionCatalog.choices(listOf(release("Games",variant="Labs"),release("Xfce",variant="Spins"),release("Kinoite"),release("KDE"),release("Workstation")))
        assertEquals(listOf("workstation","kde","kinoite","xfce","games"),choices.map { it.id })
        assertEquals(listOf(EditionGroup.OFFICIAL,EditionGroup.OFFICIAL,EditionGroup.ATOMIC,EditionGroup.SPINS,EditionGroup.LABS),choices.map { it.group })
    }
    @Test fun mergesRenamedAtomicEditionsWithoutMixingTheirSpins() {
        val choices=EditionCatalog.choices(listOf(release("Budgie-Atomic"),release("Onyx","43"),release("Budgie",variant="Spins"),release("Sway-Atomic"),release("Sericea","43"),release("Sway",variant="Spins")))
        assertEquals(listOf("onyx","sericea","sway","budgie"),choices.map { it.id })
        assertEquals(listOf("45","43"),EditionCatalog.versions(listOf(release("Onyx","43"),release("Budgie-Atomic")),"onyx").map { it.version })
    }
    @Test fun onlyOffersVersionsForSelectedEditionAndPcArchitecture() {
        val releases=listOf(release("Workstation","43"),release("Workstation","45","aarch64"),release("Workstation","44"),release("KDE","45"))
        assertEquals(listOf("44","43"),EditionCatalog.versions(releases,"workstation").map { it.version })
        assertTrue(EditionCatalog.choices(listOf(release("Workstation",arch="aarch64"))).isEmpty())
    }
    @Test fun usesCanonicalNamesForLabsVariants() {
        assertEquals("Fedora Astronomy",EditionCatalog.edition(release("Astronomy_KDE",variant="Labs")).name)
        assertEquals("Fedora Python Classroom",EditionCatalog.edition(release("Python_Classroom",variant="Labs")).name)
        assertEquals(EditionGroup.LABS,EditionCatalog.edition(release("Scientific_KDE",variant="Labs")).group)
    }
    @Test fun doesNotDuplicateVersionsWhenIotHasTwoInstallers() {
        val releases=listOf(release("IoT_Simplified_Provisioner",variant="IoT"),release("IoT"))
        assertEquals(1,EditionCatalog.choices(releases).size)
        assertEquals(listOf("IoT"),EditionCatalog.versions(releases,"iot").map { it.edition })
    }
}
