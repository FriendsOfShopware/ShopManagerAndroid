package de.shyim.shopware.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.ui.AppViewModel
import de.shyim.shopware.ui.components.ShopIconBox

@Composable
fun ManageShopsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onAddShop: () -> Unit,
    onOpenShop: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val data by vm.data.collectAsState()
    val shops = data?.shops.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
            }
            Text(
                stringResource(R.string.manage_title),
                color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            shops.forEach { shop ->
                Surface(
                    onClick = { onOpenShop(shop.id) },
                    shape = RoundedCornerShape(20.dp),
                    color = cs.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ShopIconBox(shop.tint)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shop.name, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(
                                        shop.baseUrl.removePrefix("https://").removePrefix("http://")
                                    )
                                    shop.localeCode?.let { append(" · $it") }
                                },
                                color = cs.onSurfaceVariant, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = stringResource(R.string.manage_shop_settings_cd, shop.name),
                            tint = cs.onSurfaceVariant
                        )
                    }
                }
            }

            if (shops.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.Storefront, contentDescription = null,
                        tint = cs.outline, modifier = Modifier.height(40.dp)
                    )
                    Text(
                        stringResource(R.string.common_no_shops),
                        color = cs.onSurfaceVariant, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            // Order push notifications moved to per-shop settings (registration is shop-scoped);
            // see PushNotificationCard in ShopSettingsScreen.

            Button(
                onClick = onAddShop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(50.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.home_add_shop), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

}
