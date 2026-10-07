package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Unit tests for SettingsManager.rulesetsAfterImport, what an import of routing rulesets leaves. */
class RoutingRulesetImportTest {

    private val locked = RulesetItem(id = "locked", remarks = "kept", outboundTag = "direct", locked = true)
    private val unlocked = RulesetItem(id = "unlocked", remarks = "replaced", outboundTag = "proxy")

    @Test
    fun theLockedRulesetsStayFirstAndTheOthersGiveWayToTheImport() {
        val imported = listOf(RulesetItem(id = "a", remarks = "a"), RulesetItem(id = "b", remarks = "b"))

        assertEquals(listOf(locked) + imported, SettingsManager.rulesetsAfterImport(listOf(unlocked, locked), imported))
    }

    @Test
    fun theCopyOfALockedRulesetAnExportBringsBackIsLeftOutForTheLockedOne() {
        // As exported once it was locked, and edited since: the locked one stays as it is, once.
        val copy = locked.copy(remarks = "as exported")
        val other = RulesetItem(id = "a", remarks = "a")

        val after = SettingsManager.rulesetsAfterImport(listOf(locked, unlocked), listOf(other, copy))

        assertEquals(listOf(locked, other), after)
        assertEquals(after.map { it.id }.distinct(), after.map { it.id })
    }

    @Test
    fun rulesetsWithoutAnIdAreAllImportedThoughALockedOneHasNone() {
        // An id is given to each once the list is shown.
        val lockedWithoutId = locked.copy(id = "")
        val imported = listOf(RulesetItem(remarks = "a"), RulesetItem(remarks = "b"))

        assertEquals(listOf(lockedWithoutId) + imported, SettingsManager.rulesetsAfterImport(listOf(lockedWithoutId), imported))
    }

    @Test
    fun withNothingStoredTheImportIsTakenAsItIs() {
        val imported = listOf(locked, RulesetItem(id = "a", remarks = "a"))

        assertEquals(imported, SettingsManager.rulesetsAfterImport(null, imported))
        assertEquals(imported, SettingsManager.rulesetsAfterImport(emptyList(), imported))
    }

    @Test
    fun theCopyOfALockedRulesetExportedBeforeItWasLockedIsLeftOutToo() {
        val copy = locked.copy(remarks = "as exported", locked = false)

        assertEquals(listOf(locked), SettingsManager.rulesetsAfterImport(listOf(locked), listOf(copy)))
    }

    @Test
    fun aRulesetWithTheIdOfAnUnlockedOneIsImportedInItsPlace() {
        // The unlocked one gives way to the import, so its copy is no second rule with its id.
        val copy = unlocked.copy(remarks = "as exported")

        assertEquals(listOf(locked, copy), SettingsManager.rulesetsAfterImport(listOf(locked, unlocked), listOf(copy)))
    }
}
