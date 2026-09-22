// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * The root screens' bar: the launcher icon and the app's name, read
 * from the installed package at runtime so the module carries no copy
 * of either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar() {
    val context = LocalContext.current
    val brand = remember {
        val info = context.applicationInfo
        val pm = context.packageManager
        info.loadIcon(pm).toBitmap(BRAND_ICON_PX, BRAND_ICON_PX) to info.loadLabel(pm).toString()
    }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = brand.first.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(brand.second)
            }
        }
    )
}

/** The icon is shown at 32 dp; 128 px stays crisp on any density. */
private const val BRAND_ICON_PX = 128
