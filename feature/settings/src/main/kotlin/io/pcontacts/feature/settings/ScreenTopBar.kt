// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * With an [audit] indicator a chip sits next to the version (ADR-0024/0025)
 * and opens the Dependencies screen. With [onOpenRepository] the GitHub
 * mark sits at the right end and opens the source repository through the
 * host. Public so the host can give the sign-in flow the same bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(audit: AuditIndicator? = null, onOpenRepository: (() -> Unit)? = null) {
    val context = LocalContext.current
    val brand = remember { Brand.of(context) }
    TopAppBar(
        actions = {
            onOpenRepository?.let { open ->
                IconButton(onClick = open) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = stringResource(R.string.topbar_repository_a11y)
                    )
                }
            }
        },
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
                    VersionRow(brand.version, audit)
                }
            }
        }
    )
}

@Composable
private fun VersionRow(version: String?, audit: AuditIndicator?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        version?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        audit?.let {
            Spacer(Modifier.width(8.dp))
            AuditChip(it)
        }
    }
}

/**
 * "Dependencies OK" and the like: a small outlined pill with the status dot, opening the list.
 * Without a status (runtime check off) it is a plain "Dependencies" link in the muted colour.
 */
@Composable
private fun AuditChip(audit: AuditIndicator) {
    val status = audit.status
    val tint = status?.tint() ?: MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(status?.chipRes() ?: R.string.advisory_check_open_list)
    val description = status?.let { stringResource(R.string.dependencies_indicator_a11y, stringResource(it.labelRes())) }
        ?: label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, tint, RoundedCornerShape(50))
            .clickable(onClick = audit.onOpen)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        if (status != null) {
            StatusDot(status, size = 8.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
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
