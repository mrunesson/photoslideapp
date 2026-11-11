package org.linuxalert.photo_slide_app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.linuxalert.photo_slide_app.R
import org.linuxalert.photo_slide_app.data.PhotoConnection
import org.linuxalert.photo_slide_app.domain.Photo
import org.linuxalert.photo_slide_app.domain.PhotoMetadata
import org.linuxalert.photo_slide_app.ui.theme.PhotoSlideAppTheme

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_PERMISSION_API = 37

@Composable
fun PhotoReviewApp(viewModel: PhotoReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val localNetworkPermissionGranted = remember {
        mutableStateOf(context.hasLocalNetworkPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && !localNetworkPermissionGranted.value) viewModel.reconnect()
        localNetworkPermissionGranted.value = granted || Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_API && !localNetworkPermissionGranted.value) {
            permissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    DisposableEffect(context, lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = context.hasLocalNetworkPermission()
                if (granted && !localNetworkPermissionGranted.value) viewModel.reconnect()
                localNetworkPermissionGranted.value = granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PhotoReviewScreen(
        state = state,
        localNetworkPermissionGranted = localNetworkPermissionGranted.value,
        onOpenSettings = { showSettings = true },
        onRequestLocalNetworkPermission = {
            if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_API) {
                permissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
            }
        },
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
        },
        onRate = viewModel::rate,
        onReject = viewModel::reject,
        onTagChanged = viewModel::updateTagDraft,
        onReportTag = viewModel::reportTag,
    )
    if (showSettings) {
        ServerSettingsDialog(
            address = state.serverAddress,
            error = state.connectionError ?: state.discoveryError,
            onDismiss = { showSettings = false },
            onDiscoverAgain = {
                viewModel.reconnect()
                showSettings = false
            },
            onUseAutomaticDiscovery = {
                viewModel.useAutomaticDiscovery()
                showSettings = false
            },
            onSave = { address ->
                if (viewModel.saveServerAddress(address)) showSettings = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoReviewScreen(
    state: PhotoReviewUiState,
    onOpenSettings: () -> Unit,
    onRate: (Photo, Int) -> Unit,
    onReject: (Photo) -> Unit,
    onTagChanged: (Photo, String) -> Unit,
    onReportTag: (Photo, String) -> Unit,
    localNetworkPermissionGranted: Boolean = true,
    onRequestLocalNetworkPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val newestSelectionKey = state.photos.firstOrNull()?.selectionKey
    var previousNewestSelectionKey by remember {
        mutableStateOf(newestSelectionKey)
    }
    var hasUnseenPhotos by remember { mutableStateOf(false) }

    LaunchedEffect(newestSelectionKey) {
        val previousKey = previousNewestSelectionKey
        if (previousKey != null && newestSelectionKey != null && newestSelectionKey != previousKey) {
            if (listState.firstVisibleItemIndex == 0) {
                listState.scrollToItem(0)
                hasUnseenPhotos = false
            } else {
                hasUnseenPhotos = true
            }
        } else if (newestSelectionKey == null) {
            hasUnseenPhotos = false
        }
        previousNewestSelectionKey = newestSelectionKey
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.server_settings))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = dimensionResource(R.dimen.spacing_medium)),
        ) {
            ConnectionStatus(
                connection = state.connection,
                connectionError = state.connectionError,
                discoveryError = state.discoveryError,
                automatic = state.serverAddress.isBlank(),
                discoveredServerAddress = state.discoveredServerAddress,
            )
            if (!localNetworkPermissionGranted) {
                LocalNetworkPermissionCard(
                    onRequestPermission = onRequestLocalNetworkPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
            Text(
                text = stringResource(R.string.recent_photos_count, state.photos.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
            if (state.photos.isEmpty()) {
                EmptyPhotoState(state.connection, Modifier.fillMaxWidth().weight(1f))
            } else {
                if (hasUnseenPhotos) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                                hasUnseenPhotos = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.new_photos_available))
                    }
                    Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("photo-review-list"),
                    contentPadding = PaddingValues(bottom = dimensionResource(R.dimen.spacing_large)),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_medium)),
                ) {
                    items(state.photos, key = Photo::selectionKey) { photo ->
                        PhotoCard(
                            photo = photo,
                            tagDraft = state.tagDrafts[photo.selectionKey].orEmpty(),
                            feedbackMessage = state.feedbackMessages[photo.selectionKey],
                            isFeedbackInProgress = photo.selectionKey in state.feedbackInProgress,
                            onRate = { rating -> onRate(photo, rating) },
                            onReject = { onReject(photo) },
                            onTagChanged = { tag -> onTagChanged(photo, tag) },
                            onReportTag = { tag -> onReportTag(photo, tag) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalNetworkPermissionCard(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(dimensionResource(R.dimen.spacing_medium)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_small)),
        ) {
            Text(
                text = stringResource(R.string.local_network_permission_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.local_network_permission_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.grant_local_network_access))
            }
            TextButton(
                onClick = onOpenAppSettings,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.open_app_settings))
            }
        }
    }
}

private fun Context.hasLocalNetworkPermission(): Boolean =
    Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API ||
        ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ConnectionStatus(
    connection: PhotoConnection,
    connectionError: String?,
    discoveryError: String?,
    automatic: Boolean,
    discoveredServerAddress: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_small)),
    ) {
        if (connection == PhotoConnection.CONNECTING) {
            CircularProgressIndicator(modifier = Modifier.size(dimensionResource(R.dimen.icon_size)))
        }
        Text(
            text = stringResource(
                when {
                    automatic && discoveredServerAddress == null -> R.string.searching_for_server
                    connection == PhotoConnection.CONNECTED -> R.string.connected
                    else -> R.string.connecting
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (automatic && discoveredServerAddress != null) {
        Text(
            text = stringResource(R.string.discovered_server, discoveredServerAddress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    (connectionError ?: discoveryError)?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun EmptyPhotoState(connection: PhotoConnection, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (connection == PhotoConnection.CONNECTED) R.string.empty_photos else R.string.waiting_for_server,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
        Text(
            text = stringResource(R.string.empty_photos_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhotoCard(
    photo: Photo,
    tagDraft: String,
    feedbackMessage: String?,
    isFeedbackInProgress: Boolean,
    onRate: (Int) -> Unit,
    onReject: () -> Unit,
    onTagChanged: (String) -> Unit,
    onReportTag: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius)),
        elevation = CardDefaults.cardElevation(defaultElevation = dimensionResource(R.dimen.card_elevation)),
    ) {
        Column(
            modifier = Modifier.padding(dimensionResource(R.dimen.spacing_medium)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_small)),
        ) {
            AsyncImage(
                model = photo.imageUrl,
                contentDescription = stringResource(R.string.photo_description, photo.path),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.photo_image_height))
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.corner_radius))),
            )
            Text(photo.path, style = MaterialTheme.typography.titleMedium)
            photo.album?.let { Text(stringResource(R.string.album_value, it), style = MaterialTheme.typography.bodySmall) }
            photo.metadata.capturedAt?.let {
                Text(stringResource(R.string.captured_at_value, it), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(R.string.rate_photo),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_tiny))) {
                (1..5).forEach { rating ->
                    OutlinedButton(
                        onClick = { onRate(rating) },
                        enabled = !isFeedbackInProgress,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = dimensionResource(R.dimen.spacing_tiny)),
                    ) {
                        Text(stringResource(R.string.rating_choice, rating))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_small)),
            ) {
                Button(onClick = onReject, enabled = !isFeedbackInProgress) {
                    Text(stringResource(R.string.reject_photo))
                }
                if (isFeedbackInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(dimensionResource(R.dimen.icon_size)))
                }
            }
            if (photo.metadata.exif.isNotEmpty()) {
                Text(stringResource(R.string.faulty_tag_prompt), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_tiny)),
                ) {
                    photo.metadata.exif.keys.sorted().forEach { tag ->
                        AssistChip(
                            onClick = { onReportTag(tag) },
                            enabled = !isFeedbackInProgress,
                            label = { Text(tag) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = tagDraft,
                onValueChange = onTagChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.exif_tag_name)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                singleLine = true,
                enabled = !isFeedbackInProgress,
            )
            OutlinedButton(
                onClick = { onReportTag(tagDraft) },
                enabled = !isFeedbackInProgress && tagDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.report_faulty_tag))
            }
            feedbackMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it == stringResource(R.string.feedback_saved)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerSettingsDialog(
    address: String,
    error: String?,
    onDismiss: () -> Unit,
    onDiscoverAgain: () -> Unit,
    onUseAutomaticDiscovery: () -> Unit,
    onSave: (String) -> Unit,
) {
    var editedAddress by rememberSaveable(address) { mutableStateOf(address) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_small))) {
                Text(
                    stringResource(R.string.server_address_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = editedAddress,
                    onValueChange = { editedAddress = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
                TextButton(
                    onClick = if (address.isBlank()) onDiscoverAgain else onUseAutomaticDiscovery,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(stringResource(if (address.isBlank()) R.string.discover_again else R.string.use_automatic_discovery))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editedAddress) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun PhotoReviewEmptyPreview() {
    PhotoSlideAppTheme {
        PhotoReviewScreen(
            state = PhotoReviewUiState(connection = PhotoConnection.CONNECTED),
            onOpenSettings = {},
            onRate = { _, _ -> },
            onReject = {},
            onTagChanged = { _, _ -> },
            onReportTag = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PhotoReviewContentPreview() {
    val photo = Photo(
        path = "Coast/sunset.jpg",
        album = "Coast",
        metadata = PhotoMetadata(
            capturedAt = "2026-07-14 19:42:00",
            latitude = 36.0,
            longitude = -122.0,
            exif = mapOf("Make" to "Camera", "LensModel" to "Wide"),
        ),
        imageUrl = "https://example.com/preview.jpg",
        shownAt = "2026-11-08T10:00:00Z",
    )
    PhotoSlideAppTheme {
        PhotoReviewScreen(
            state = PhotoReviewUiState(
                photos = listOf(photo),
                connection = PhotoConnection.CONNECTED,
                tagDrafts = mapOf(photo.selectionKey to "Make"),
            ),
            onOpenSettings = {},
            onRate = { _, _ -> },
            onReject = {},
            onTagChanged = { _, _ -> },
            onReportTag = { _, _ -> },
        )
    }
}