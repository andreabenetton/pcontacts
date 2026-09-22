// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** The app bar every inner screen uses: a title and the platform back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.contacts_access_back)
                )
            }
        }
    )
}

/**
 * The root screens' bar: the launcher icon, the app's name and, in
 * small type under it, the installed version — all read from the
 * package at runtime so the module carries no copy of any of them.
 * Public so the host can give the sign-in flow the same bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar() {
    val context = LocalContext.current
    val brand = remember { Brand.of(context) }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = brand.icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(brand.name)
                    brand.version?.let { version ->
                        Text(
                            text = version,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

private class Brand(val icon: Bitmap, val name: String, val version: String?) {
    companion object {
        fun of(context: Context): Brand {
            val info = context.applicationInfo
            val pm = context.packageManager
            return Brand(
                icon = info.loadIcon(pm).toBitmap(BRAND_ICON_PX, BRAND_ICON_PX),
                name = info.loadLabel(pm).toString(),
                version = versionName(pm, context.packageName)
            )
        }

        private fun versionName(pm: PackageManager, packageName: String): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0).versionName
            }
    }
}

/** The icon is shown at 32 dp; 128 px stays crisp on any density. */
private const val BRAND_ICON_PX = 128
