package de.shyim.shopware

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.shyim.shopware.data.sync.Notifications
import de.shyim.shopware.update.InAppUpdateController
import de.shyim.shopware.ui.AppViewModel
import de.shyim.shopware.ui.components.ScrollingTab
import de.shyim.shopware.ui.SyncState
import de.shyim.shopware.ui.connect.ConnectScreen
import de.shyim.shopware.ui.onboarding.OnboardingScreen
import de.shyim.shopware.ui.screens.CustomerDetailScreen
import de.shyim.shopware.ui.screens.CustomersTab
import de.shyim.shopware.ui.screens.HomeDashboard
import de.shyim.shopware.ui.screens.ManageShopsScreen
import de.shyim.shopware.ui.screens.MediaScreen
import de.shyim.shopware.ui.screens.MoreTab
import de.shyim.shopware.ui.screens.OrderDetailScreen
import de.shyim.shopware.ui.screens.ProductDetailScreen
import de.shyim.shopware.ui.screens.ProductsScreen
import de.shyim.shopware.ui.screens.PromosScreen
import de.shyim.shopware.ui.screens.ReportsScreen
import de.shyim.shopware.ui.screens.ReviewInboxScreen
import de.shyim.shopware.ui.screens.ShopSettingsScreen
import de.shyim.shopware.ui.screens.ShopSwitcherHeader
import de.shyim.shopware.ui.screens.OrdersTab
import de.shyim.shopware.ui.theme.ShopwareTheme

enum class AppTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val requiredScope: String? = null, // entity read access; null = always visible
) {
    Home(R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Orders(R.string.nav_orders, Icons.Outlined.ReceiptLong, Icons.Filled.ReceiptLong, "order"),
    Customers(R.string.nav_customers, Icons.Outlined.Group, Icons.Filled.Group, "customer"),
    Reports(R.string.nav_reports, Icons.Outlined.BarChart, Icons.Filled.BarChart, "order"),
    More(R.string.nav_more, Icons.Outlined.Apps, Icons.Filled.Apps),
}

// Sub-destinations of the More tab. Rendered inside MainScreen so the navigation rail/bar
// stays visible (instead of pushing a full-screen route that hides it).
enum class MoreDest { Products, Promos, Media, Reviews }

class MainActivity : ComponentActivity() {
    // registerForActivityResult (inside the controller) must run before the activity is STARTED
    private lateinit var inAppUpdate: InAppUpdateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inAppUpdate = InAppUpdateController(this)
        lifecycle.addObserver(inAppUpdate)
        val notificationShopId = intent.getStringExtra(Notifications.EXTRA_SHOP_ID)
        // From an order push: the shop's APP_URL + order id (resolved to a route in AppRoot)
        val pushShopUrl = intent.getStringExtra(EXTRA_PUSH_SHOP_URL)
        val pushOrderId = intent.getStringExtra(EXTRA_PUSH_ORDER_ID)
        setContent {
            ShopwareTheme {
                // Routes outside MainScreen draw no background of their own; without this
                // the (light) window background shows through and breaks dark mode.
                // The top inset keeps every route's header clear of the status bar
                // (MainScreen's nav scaffold stays full-bleed and re-pads its own content).
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    AppRoot(
                        initialShopId = notificationShopId,
                        pushShopUrl = pushShopUrl,
                        pushOrderId = pushOrderId,
                        inAppUpdate = inAppUpdate,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PUSH_SHOP_URL = "pushShopUrl"
        const val EXTRA_PUSH_ORDER_ID = "pushOrderId"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRoot(
    initialShopId: String? = null,
    pushShopUrl: String? = null,
    pushOrderId: String? = null,
    inAppUpdate: InAppUpdateController? = null,
    vm: AppViewModel = viewModel(),
) {
    val data by vm.data.collectAsState()
    val d = data
    if (d == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator(modifier = Modifier.size(56.dp))
        }
        return
    }

    // Notification tap carries the shop it was about
    LaunchedEffect(initialShopId) {
        if (initialShopId != null && d.shops.any { it.id == initialShopId }) {
            vm.selectShop(initialShopId)
        }
    }

    // Order push tap: match the shop by its APP_URL, select it and deep-link to the order
    val nav = rememberNavController()
    LaunchedEffect(pushShopUrl, pushOrderId) {
        if (pushOrderId.isNullOrBlank()) return@LaunchedEffect
        val norm = { s: String -> s.trimEnd('/') }
        val shop = pushShopUrl?.let { url -> d.shops.firstOrNull { norm(it.baseUrl) == norm(url) } }
            ?: d.shops.firstOrNull { it.id == d.selectedShopId }
            ?: d.shops.firstOrNull()
        if (shop != null) {
            vm.selectShop(shop.id)
            nav.navigate("order/${shop.id}/$pushOrderId")
        }
    }

    // "Update downloaded · Restart" prompt for a finished flexible in-app update
    val snackbarHostState = remember { SnackbarHostState() }
    if (inAppUpdate != null) {
        val ready = inAppUpdate.downloadReady
        val message = stringResource(R.string.update_downloaded)
        val action = stringResource(R.string.update_restart)
        LaunchedEffect(ready) {
            if (ready) {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = action,
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) inAppUpdate.completeUpdate()
            }
        }
    }

    val start = remember { if (d.shops.isEmpty()) "onboarding" else "main" }

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(onConnect = { nav.navigate("connect") })
        }
        composable("connect") {
            ConnectScreen(
                onClose = {
                    if (!nav.popBackStack()) nav.navigate("onboarding")
                },
                onFinished = { shopId ->
                    vm.refresh(shopId)
                    nav.navigate("main") { popUpTo(0) { inclusive = true } }
                }
            )
        }
        composable("main") {
            MainScreen(
                vm = vm,
                onAddShop = { nav.navigate("connect") },
                onManageShops = { nav.navigate("manage") },
                onOpenOrder = { shopId, orderId -> nav.navigate("order/$shopId/$orderId") },
                onOpenCustomer = { shopId, customerId -> nav.navigate("customer/$shopId/$customerId") },
            )
        }
        // Products/Promos/Media/Reviews render inside MainScreen's More tab (rail stays visible),
        // so they have no standalone routes.
        composable("customer/{shopId}/{customerId}") { entry ->
            val shopId = entry.arguments?.getString("shopId") ?: return@composable
            val customerId = entry.arguments?.getString("customerId") ?: return@composable
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Outside the nav scaffold: pad the bottom system bar ourselves (edge-to-edge).
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                CustomerDetailScreen(
                    shopId = shopId,
                    customerId = customerId,
                    onBack = { nav.popBackStack() },
                    onOpenOrder = { orderId -> nav.navigate("order/$shopId/$orderId") }
                )
            }
        }
        composable("manage") {
            ManageShopsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onAddShop = { nav.navigate("connect") },
                onOpenShop = { shopId -> nav.navigate("shop-settings/$shopId") }
            )
        }
        composable("shop-settings/{shopId}") { entry ->
            val shopId = entry.arguments?.getString("shopId") ?: return@composable
            ShopSettingsScreen(
                vm = vm,
                shopId = shopId,
                onBack = { nav.popBackStack() }
            )
        }
        composable("order/{shopId}/{orderId}") { entry ->
            val shopId = entry.arguments?.getString("shopId") ?: return@composable
            val orderId = entry.arguments?.getString("orderId") ?: return@composable
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Outside the nav scaffold: pad the bottom system bar ourselves (edge-to-edge).
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                OrderDetailScreen(
                    shopId = shopId,
                    orderId = orderId,
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
        // Snackbar overlay (above the nav scaffold), anchored to the bottom
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

@Composable
fun MainScreen(
    vm: AppViewModel,
    onAddShop: () -> Unit,
    onManageShops: () -> Unit,
    onOpenOrder: (String, String) -> Unit,
    onOpenCustomer: (String, String) -> Unit,
) {
    val data by vm.data.collectAsState()
    val sync by vm.sync.collectAsState()
    val d = data ?: return

    val shop = d.shops.firstOrNull { it.id == d.selectedShopId } ?: d.shops.firstOrNull()
    if (shop == null) {
        NoShopsScreen(onAddShop)
        return
    }
    val snapshot = d.snapshots[shop.id]
    val syncState = sync[shop.id] ?: SyncState.Idle
    var tab by rememberSaveable { mutableStateOf(AppTab.Home) }
    // Which More sub-screen is open (null = the More menu itself). Kept here so the rail stays.
    var moreDest by rememberSaveable { mutableStateOf<MoreDest?>(null) }
    // A product detail open on top of the Products list (null = the list). Rail stays visible.
    var productDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped by the Reports tab's pull-to-refresh to re-fetch the analytics KPIs.
    var reportsRefreshSignal by remember { mutableIntStateOf(0) }

    // Tabs whose entity the connected login cannot read are hidden (wizard ACL probes)
    val visibleTabs = AppTab.entries.filter { it.requiredScope?.let(shop::canRead) != false }
    if (tab !in visibleTabs) tab = AppTab.Home

    LaunchedEffect(shop.id) {
        vm.refreshIfStale(shop)
    }

    // System back unwinds the More stack: product detail → list → menu (no route pops)
    BackHandler(enabled = tab == AppTab.More && moreDest != null) {
        if (productDetailId != null) productDetailId = null else moreDest = null
    }

    val expanded = de.shyim.shopware.ui.isExpandedWidth()
    NavigationSuiteScaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        navigationSuiteItems = {
            visibleTabs.forEach { item ->
                val selected = tab == item
                item(
                    selected = selected,
                    onClick = {
                        // Re-tapping More returns to its menu; switching tabs also resets it
                        if (item == AppTab.More && tab == AppTab.More) { moreDest = null; productDetailId = null }
                        tab = item
                    },
                    icon = {
                        Icon(
                            if (selected) item.selectedIcon else item.icon,
                            contentDescription = stringResource(item.labelRes)
                        )
                    },
                    label = { Text(stringResource(item.labelRes)) }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Status-bar inset is applied once at the root Surface (covers every route).
            // Listing tabs own their scrolling (LazyColumn in ListingScaffold) and render
            // fillMaxSize; snapshot tabs share a scrolling column. To migrate a tab,
            // move its case out of ScrollingTab.
            when (tab) {
                AppTab.Orders -> OrdersTab(
                    shop = shop,
                    snapshot = snapshot,
                    openOrderRoute = { orderId -> onOpenOrder(shop.id, orderId) },
                )
                AppTab.Home -> ScrollingTab(
                    maxWidth = if (expanded) 1100.dp else 560.dp,
                    isRefreshing = syncState == SyncState.Syncing,
                    onRefresh = { vm.refresh(shop.id) },
                ) {
                    ShopSwitcherHeader(
                        shop = shop,
                        shops = d.shops,
                        syncState = syncState,
                        lastSyncMs = snapshot?.lastSyncEpochMs,
                        onSelectShop = { vm.selectShop(it) },
                        onRefresh = { vm.refresh(shop.id) },
                        onAddShop = onAddShop,
                        onManageShops = onManageShops,
                    )
                    HomeDashboard(
                        shop = shop,
                        snapshot = snapshot,
                        syncState = syncState,
                        onRefresh = { vm.refresh(shop.id) },
                        onOrderClick = { orderId -> onOpenOrder(shop.id, orderId) },
                        // Keep the rail: open Reviews inside the More tab instead of a full route
                        onOpenReviews = { tab = AppTab.More; moreDest = MoreDest.Reviews },
                        twoColumn = expanded,
                    )
                }
                AppTab.Customers -> CustomersTab(
                    shop = shop,
                    snapshot = snapshot,
                    openCustomerRoute = { customerId -> onOpenCustomer(shop.id, customerId) },
                    onOpenOrder = { orderId -> onOpenOrder(shop.id, orderId) },
                )
                AppTab.More -> when (moreDest) {
                    // The list/grid screens own their scrolling — render at fillMaxSize, rail stays
                    MoreDest.Products -> productDetailId?.let { pid ->
                        ProductDetailScreen(
                            shopId = shop.id,
                            productId = pid,
                            onBack = { productDetailId = null },
                        )
                    } ?: ProductsScreen(
                        shop = shop,
                        onBack = { moreDest = null },
                        onOpenProduct = { productDetailId = it },
                    )
                    MoreDest.Promos -> PromosScreen(
                        shop = shop,
                        snapshot = snapshot,
                        onBack = { moreDest = null },
                    )
                    MoreDest.Media -> MediaScreen(shop = shop, onBack = { moreDest = null })
                    MoreDest.Reviews -> ReviewInboxScreen(shopId = shop.id, onBack = { moreDest = null })
                    null -> ScrollingTab(maxWidth = if (expanded) 1100.dp else 560.dp) {
                        MoreTab(
                            shop = shop,
                            onOpenProducts = { moreDest = MoreDest.Products },
                            onOpenPromos = { moreDest = MoreDest.Promos },
                            onOpenMedia = { moreDest = MoreDest.Media },
                            onOpenReviews = { moreDest = MoreDest.Reviews },
                        )
                    }
                }
                AppTab.Reports -> ScrollingTab(
                    maxWidth = if (expanded) 1100.dp else 560.dp,
                    isRefreshing = syncState == SyncState.Syncing,
                    onRefresh = { vm.refresh(shop.id); reportsRefreshSignal++ },
                ) {
                    ReportsScreen(
                        shop = shop,
                        allShops = d.shops,
                        refreshSignal = reportsRefreshSignal,
                    )
                }
            }
        }
    }

}

@Composable
private fun NoShopsScreen(onAddShop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Storefront, contentDescription = null,
            tint = cs.outline, modifier = Modifier.size(48.dp)
        )
        Text(
            stringResource(R.string.common_no_shops),
            color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp)
        )
        Button(
            onClick = onAddShop,
            modifier = Modifier
                .padding(top = 18.dp)
                .height(50.dp)
        ) { Text(stringResource(R.string.home_connect_shop)) }
    }
}
