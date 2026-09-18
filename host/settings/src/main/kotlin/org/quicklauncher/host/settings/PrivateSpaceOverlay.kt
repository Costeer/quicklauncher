package org.quicklauncher.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PrivateSpaceOverlay(
    state: PrivateSpaceState,
    protection: SecureOverlayProtection,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onLaunch: (AppActivityIdentity) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale > 1f
    DisposableEffect(protection) {
        val session = protection.begin()
        onDispose(session::close)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.private_space_title),
                        Modifier.semantics { heading() },
                    )
                },
                actions = {
                    if (!largeText) {
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.private_space_settings))
                        }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                    }
                },
            )
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
        if (largeText) {
            Column(contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.private_space_settings))
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
                PrivateSpaceContent(
                    state,
                    onLock,
                    onUnlock,
                    onLaunch,
                    Modifier.fillMaxWidth().weight(1f),
                )
            }
        } else {
            PrivateSpaceContent(state, onLock, onUnlock, onLaunch, contentModifier)
        }
    }
}

@Composable
private fun PrivateSpaceContent(
    state: PrivateSpaceState,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onLaunch: (AppActivityIdentity) -> Unit,
    modifier: Modifier,
) {
    when {
        state.transition == ProfileTransition.LOCKING ->
            Explanation(stringResource(R.string.private_space_locking), modifier)
        state.transition == ProfileTransition.UNLOCKING ->
            Explanation(stringResource(R.string.private_space_unlocking), modifier)
        state.availability == ProfileAvailability.LOCKED -> LockedPrivateSpace(
            onUnlock = onUnlock,
            modifier = modifier,
        )
        state.availability != ProfileAvailability.AVAILABLE ->
            Explanation(stringResource(R.string.private_space_unavailable), modifier)
        else -> UnlockedPrivateSpace(state, onLock, onLaunch, modifier)
    }
}

@Composable
private fun LockedPrivateSpace(onUnlock: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.private_space_locked), style = MaterialTheme.typography.titleMedium)
        Button(onClick = onUnlock) { Text(stringResource(R.string.private_space_unlock)) }
    }
}

@Composable
private fun UnlockedPrivateSpace(
    state: PrivateSpaceState,
    onLock: () -> Unit,
    onLaunch: (AppActivityIdentity) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onLock) { Text(stringResource(R.string.private_space_lock)) }
        if (state.items.isEmpty()) {
            Text(stringResource(R.string.private_space_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.identity.toString() }) { item ->
                    Button(
                        onClick = { onLaunch(item.identity) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(item.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun Explanation(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleMedium)
}
