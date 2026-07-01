package de.shyim.shopware.ui.connect

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.api.ApiError
import de.shyim.shopware.api.PlainAuth
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.ShopwareHttp
import de.shyim.shopware.data.AppRepository
import de.shyim.shopware.data.PushRegisterResult
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ShopAuth
import de.shyim.shopware.api.LanguageOption
import de.shyim.shopware.data.store.Crypto
import kotlinx.coroutines.launch
import java.util.UUID

const val SYSTEM_LANGUAGE_ID = "2fbb5fe2e29a4d70aa5854ce7ce3e20b"

data class ScopeProbe(val entity: String, val label: String, val ok: Boolean)

data class VerifyState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val version: String? = null,
    val scopes: List<ScopeProbe> = emptyList(),
    val error: String? = null,
)

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    private fun appString(res: Int, vararg args: Any) =
        getApplication<Application>().getString(res, *args)

    var step by mutableIntStateOf(0) // 0 url · 1 credentials · 2 verify · 3 personalize
        private set
    var busy by mutableStateOf(false)
        private set

    var url by mutableStateOf("")
    var urlError by mutableStateOf<String?>(null)

    var username by mutableStateOf("")
    var password by mutableStateOf("")

    // The ShopApi created for verification — its client holds the rotating refresh
    // token that finish() persists (the password itself is never stored).
    private var verifyApi: ShopApi? = null

    var verify by mutableStateOf(VerifyState())
        private set

    var shopName by mutableStateOf("")
    var tintIndex by mutableIntStateOf(0)
    var currency by mutableStateOf("EUR")
    var dailyTarget by mutableStateOf("")
    var languages by mutableStateOf<List<LanguageOption>>(emptyList())
        private set
    var selectedLanguage by mutableStateOf<LanguageOption?>(null)

    var normalizedUrl = ""
        private set

    fun submitUrl() {
        if (busy) return
        busy = true
        urlError = null
        viewModelScope.launch {
            val norm = ShopwareHttp.normalizeBaseUrl(url)
            ShopwareHttp.probeShopware(norm).fold(
                onSuccess = {
                    normalizedUrl = norm
                    step = 1
                },
                onFailure = { e ->
                    urlError = when (e) {
                        is ApiError -> e.message
                        else -> appString(R.string.wizard_url_unreachable, norm)
                    }
                }
            )
            busy = false
        }
    }

    val credsValid: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun startVerify() {
        step = 2
        verify = VerifyState(running = true)
        viewModelScope.launch {
            val api = ShopApi(normalizedUrl, PlainAuth.Password(username.trim(), password))
            verifyApi = api
            try {
                val version = api.instance.version()
                val labels = mapOf(
                    "order" to appString(R.string.nav_orders),
                    "product" to appString(R.string.wizard_probe_products),
                    "customer" to appString(R.string.nav_customers),
                    "promotion" to appString(R.string.promos_title),
                    "product_review" to appString(R.string.reviews_title),
                )
                val scopes = AppRepository.PROBE_ENTITIES.map { entity ->
                    ScopeProbe(entity, labels[entity] ?: entity, api.instance.probe(entity))
                }
                api.instance.defaultCurrencyIso()?.let { currency = it }
                shopName = api.instance.defaultSalesChannelName()
                    ?: Uri.parse(normalizedUrl).host.orEmpty()
                languages = runCatching { api.instance.languages() }.getOrDefault(emptyList())
                selectedLanguage = languages.firstOrNull { it.id == SYSTEM_LANGUAGE_ID }
                    ?: languages.firstOrNull()
                verify = VerifyState(connected = true, version = version, scopes = scopes)
            } catch (e: ApiError.Forbidden) {
                val missing = e.missingPrivileges.takeIf { it.isNotEmpty() }
                    ?.let { " " + appString(R.string.wizard_missing_privileges, it.joinToString()) }
                verify = VerifyState(
                    error = (e.message ?: appString(R.string.wizard_access_denied)) + missing.orEmpty()
                )
            } catch (e: Exception) {
                verify = VerifyState(error = e.message ?: appString(R.string.wizard_could_not_connect))
            }
        }
    }

    val canLeaveVerify: Boolean
        get() = verify.connected && verify.scopes.any { it.ok }

    fun toPersonalize() {
        step = 3
    }

    fun retryFromCredentials() {
        verify = VerifyState()
        step = 1
    }

    fun goBack(): Boolean {
        if (busy || verify.running) return true
        return when (step) {
            0 -> false
            2 -> { verify = VerifyState(); step = 1; true }
            else -> { step -= 1; true }
        }
    }

    // Set when the connected shop is missing the FroshMobilePush app (ce_fcn 404). The
    // screen shows a dialog; dismissing it proceeds to the dashboard via [proceedAfterConnect].
    var pushAppMissing by mutableStateOf(false)
        private set
    private var pendingShopId: String? = null
    private var pendingOnDone: ((String) -> Unit)? = null

    fun finish(onDone: (String) -> Unit) {
        if (busy) return
        val refreshToken = verifyApi?.currentRefreshToken
        if (refreshToken == null) {
            // verify never completed a grant — should not happen; bounce to credentials
            retryFromCredentials()
            return
        }
        busy = true
        viewModelScope.launch {
            val auth = ShopAuth.Admin(username.trim(), Crypto.encrypt(refreshToken))
            val shop = ConnectedShop(
                id = UUID.randomUUID().toString(),
                name = shopName.trim().ifBlank { appString(R.string.wizard_default_shop_name) },
                baseUrl = normalizedUrl,
                auth = auth,
                tintIndex = tintIndex,
                currency = currency,
                dailyTarget = dailyTarget.trim().toDoubleOrNull(),
                languageId = selectedLanguage?.id,
                localeCode = selectedLanguage?.localeCode,
                scopes = verify.scopes.associate { it.entity to it.ok },
            )
            repo.addShop(shop)
            // Register this device for order pushes on the freshly connected shop, and detect a
            // missing push app (ce_fcn 404) so we can prompt the user to install it.
            val app = getApplication<Application>() as ShopwareApp
            val result = app.registerPushForShopChecked(shop)
            busy = false
            if (result == PushRegisterResult.AppNotInstalled) {
                pendingShopId = shop.id
                pendingOnDone = onDone
                pushAppMissing = true
            } else {
                onDone(shop.id)
            }
        }
    }

    // Dismiss the "push app not installed" dialog and continue to the dashboard.
    fun proceedAfterConnect() {
        pushAppMissing = false
        val id = pendingShopId ?: return
        pendingOnDone?.invoke(id)
        pendingShopId = null
        pendingOnDone = null
    }
}
