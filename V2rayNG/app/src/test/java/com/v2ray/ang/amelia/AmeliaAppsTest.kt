package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmeliaAppsTest {

    private val builtIn = setOf("ru.sberbankmobile", "ru.rostel", "com.wildberries.ru")

    @Test
    fun effectiveIsBuiltInMinusRemovedPlusAdded() {
        assertEquals(builtIn, AmeliaApps.effective(builtIn, emptySet(), emptySet()))
        assertEquals(
            setOf("ru.sberbankmobile", "com.wildberries.ru", "org.example.bank"),
            AmeliaApps.effective(builtIn, setOf("org.example.bank"), setOf("ru.rostel")),
        )
    }

    @Test
    fun personsChangesSurviveAnUpdateAndNewBuiltInsArrive() {
        // the person removed Gosuslugi and added their own app
        val (added, removed) = AmeliaApps.toggle(builtIn, emptySet(), emptySet(), "ru.rostel", bypass = false)
            .let { (a, r) -> AmeliaApps.toggle(builtIn, a, r, "org.example.bank", bypass = true) }
        // the next version ships one more package in the built-in list
        val newBuiltIn = builtIn + "ru.ozon.app.android"
        val result = AmeliaApps.effective(newBuiltIn, added, removed)
        assertTrue("ru.ozon.app.android" in result)
        assertTrue("org.example.bank" in result)
        assertFalse("ru.rostel" in result)
    }

    @Test
    fun toggleStoresOnlyDifferencesFromTheBuiltInList() {
        // switching a built-in app off and on again leaves nothing stored
        val off = AmeliaApps.toggle(builtIn, emptySet(), emptySet(), "ru.rostel", bypass = false)
        assertEquals(emptySet<String>() to setOf("ru.rostel"), off)
        val on = AmeliaApps.toggle(builtIn, off.first, off.second, "ru.rostel", bypass = true)
        assertEquals(emptySet<String>() to emptySet<String>(), on)
        // an app of the person's own: added, then removed again
        val add = AmeliaApps.toggle(builtIn, emptySet(), emptySet(), "org.example", bypass = true)
        assertEquals(setOf("org.example") to emptySet<String>(), add)
        assertEquals(emptySet<String>() to emptySet<String>(), AmeliaApps.toggle(builtIn, add.first, add.second, "org.example", false))
    }

    @Test
    fun builtInListIsWellFormed() {
        val pkgs = AmeliaApps.BUILT_IN.map { it.pkg }
        assertEquals(pkgs.size, pkgs.toSet().size, "duplicate package")
        val name = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        pkgs.forEach { assertTrue(name.matches(it), "bad package name $it") }
        assertTrue(AmeliaApps.BUILT_IN.all { it.label.isNotBlank() })
        // the ones people ask about most
        listOf("ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.rostel", "ru.vk.store", "ru.oneme.app")
            .forEach { assertTrue(it in AmeliaApps.BUILT_IN_PACKAGES, it) }
    }
}
