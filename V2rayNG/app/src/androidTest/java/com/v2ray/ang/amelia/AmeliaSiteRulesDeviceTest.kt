package com.v2ray.ang.amelia

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs on an emulator with its own app data; no subscription or live VPN is required. */
@RunWith(AndroidJUnit4::class)
class AmeliaSiteRulesDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preference = "amelia_site_rules_v1"
    private var previous: String? = null
    private var ui by mutableStateOf(AmeliaUiState(siteRulesReady = true))

    @Before
    fun saveOriginalRules() {
        previous = MmkvManager.decodeSettingsString(preference)
        MmkvManager.encodeSettings(preference, "{}")
    }

    @After
    fun restoreOriginalRules() {
        MmkvManager.encodeSettings(preference, previous)
        AmeliaRouting.apply(context)
    }

    @Test
    fun storedRulesSurviveRoutingRebuildAndDeletionRestoresDefaults() {
        AmeliaRouting.saveSiteRule(context, "kinopoisk.ru", true)
        AmeliaRouting.apply(context)
        assertEquals(listOf(AmeliaRouting.SiteRule("kinopoisk.ru", true)), AmeliaRouting.loadSiteRules())
        AmeliaRouting.saveSiteRule(context, "kinopoisk.ru", false)
        AmeliaRouting.apply(context)
        assertFalse(AmeliaRouting.loadSiteRules().single().viaVpn)
        AmeliaRouting.saveSiteRule(context, "kinopoisk.ru", null)
        AmeliaRouting.apply(context)
        assertEquals(emptyList<AmeliaRouting.SiteRule>(), AmeliaRouting.loadSiteRules())
    }

    @Test
    fun failedRebuildRollsBackBothSavedAndGeneratedRules() {
        AmeliaRouting.saveSiteRule(context, "example.com", true)
        val generated = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_RULESET)
        try {
            AmeliaRouting.saveSiteRule("example.com", false) {
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_RULESET, "[]")
                throw java.io.IOException("Simulated routing write failure")
            }
            org.junit.Assert.fail("The failure must be reported")
        } catch (_: java.io.IOException) {
            assertEquals(listOf(AmeliaRouting.SiteRule("example.com", true)), AmeliaRouting.loadSiteRules())
            assertEquals(generated, MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_RULESET))
        }
    }

    @Test
    fun backgroundRebuildWaitsForPendingSiteRuleWrite() {
        val worker = Executors.newFixedThreadPool(2)
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rebuilding = CountDownLatch(1)
        try {
            val save = worker.submit {
                AmeliaRouting.saveSiteRule("example.com", false) {
                    saving.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            check(saving.await(5, TimeUnit.SECONDS))
            val rebuild = worker.submit {
                rebuilding.countDown()
                AmeliaRouting.apply(context)
            }
            check(rebuilding.await(5, TimeUnit.SECONDS))
            try {
                rebuild.get(300, TimeUnit.MILLISECONDS)
                org.junit.Assert.fail("Background rebuild raced the pending rule write")
            } catch (_: TimeoutException) {
                release.countDown()
            }
            save.get(5, TimeUnit.SECONDS)
            rebuild.get(5, TimeUnit.SECONDS)
            val generated = MmkvManager.decodeRoutingRulesets().orEmpty()
            assertEquals(AppConfig.TAG_DIRECT, generated.single { it.domain == listOf("domain:example.com") }.outboundTag)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    private fun showEditor(keyboard: Boolean = false) {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(keyboard) {
                if (keyboard) inputMode.requestInputMode(InputMode.Keyboard)
            }
            MaterialTheme {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                    SiteRulesSection(ui, onSet = { input, viaVpn ->
                        val domain = AmeliaRouting.siteDomain(input)
                        ui = if (domain == null) ui.copy(siteRulesError = R.string.amelia_site_bad)
                        else ui.copy(
                            siteRules = AmeliaRouting.changeSiteRules(ui.siteRules, domain, viaVpn),
                            siteRulesError = null,
                        )
                    }, onReload = {})
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun emptyFormAndInvalidInputDoNotCreateRules() {
        showEditor()
        compose.onNodeWithText(text(R.string.amelia_sites_vpn)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.amelia_site_hint)).performTextInput("not a site")
        compose.onNodeWithText(text(R.string.amelia_sites_vpn)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.amelia_site_bad)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<AmeliaRouting.SiteRule>(), ui.siteRules) }
    }

    @Test
    fun addChangeAndDeleteWhileKeyboardIsVisible() {
        showEditor()
        compose.onNodeWithText(text(R.string.amelia_site_hint)).performTextInput("https://www.example.com/path")
        compose.onNodeWithText(text(R.string.amelia_sites_vpn)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.amelia_sites_rule_vpn, "example.com")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.amelia_sites_direct)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.amelia_sites_rule_direct, "example.com")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.amelia_sites_delete, "example.com")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.amelia_sites_empty)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun busyAndFailedLoadCannotOverwriteSavedRules() {
        ui = AmeliaUiState(siteRulesBusy = true)
        showEditor()
        compose.onNodeWithText(text(R.string.amelia_site_hint)).assertIsNotEnabled()
        compose.runOnIdle { ui = AmeliaUiState(siteRulesError = R.string.amelia_sites_failed) }
        compose.onNodeWithText(text(R.string.amelia_sites_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.amelia_site_hint)).assertIsNotEnabled()
    }

    @Test
    fun keyboardAndDpadActivateTheSameActions() {
        showEditor(keyboard = true)
        compose.onNodeWithText(text(R.string.amelia_site_hint)).performTextInput("example.com")
        compose.onNodeWithText(text(R.string.amelia_sites_vpn)).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(text(R.string.amelia_sites_vpn)).assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText(text(R.string.amelia_sites_rule_vpn, "example.com")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.amelia_sites_direct)).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(text(R.string.amelia_sites_direct)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText(text(R.string.amelia_sites_rule_direct, "example.com")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun actionsBelowTheRouteResultUseTheShownDomain() {
        val chosen = mutableListOf<AmeliaRouting.SiteRule>()
        ui = ui.copy(routeCheck = AmeliaRouteCheck.Result(
            "www.example.com", AmeliaRouteCheck.Route.VPN, AmeliaRouteCheck.Reason.DEFAULT,
            siteRuleDomain = "example.com",
        ))
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            MaterialTheme {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                    SettingsBypassSection(ui, SettingsCallbacks(
                        onOpen = {},
                        onCheckRoute = {},
                        onRefreshSubscription = {},
                        onSetSiteRule = { domain, viaVpn -> chosen += AmeliaRouting.SiteRule(domain, checkNotNull(viaVpn)) },
                        onReloadSiteRules = {},
                    ))
                }
            }
        }
        compose.onNodeWithText(text(R.string.amelia_route_set_vpn, "example.com")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.amelia_route_set_direct, "example.com")).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(text(R.string.amelia_route_set_direct, "example.com")).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(listOf(
                AmeliaRouting.SiteRule("example.com", true),
                AmeliaRouting.SiteRule("example.com", false),
            ), chosen)
        }
    }

    @Test
    fun uncommittedAddressSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                    SiteRulesSection(ui, onSet = { _, _ -> }, onReload = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.amelia_site_hint)).performTextInput("shop.example.com")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.amelia_site_hint)).assertTextContains("shop.example.com")
    }
}
