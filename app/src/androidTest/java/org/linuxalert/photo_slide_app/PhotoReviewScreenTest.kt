package org.linuxalert.photo_slide_app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.linuxalert.photo_slide_app.data.PhotoConnection
import org.linuxalert.photo_slide_app.domain.Photo
import org.linuxalert.photo_slide_app.domain.PhotoMetadata
import org.linuxalert.photo_slide_app.ui.PhotoReviewScreen
import org.linuxalert.photo_slide_app.ui.PhotoReviewUiState
import org.linuxalert.photo_slide_app.ui.theme.PhotoSlideAppTheme

@RunWith(AndroidJUnit4::class)
class PhotoReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialPhotosShowNewestPhoto() {
        val state = mutableStateOf(reviewState(emptyList()))
        composeRule.setContent {
            PhotoSlideAppTheme {
                PhotoReviewScreen(
                    state = state.value,
                    onOpenSettings = {},
                    onRate = { _, _ -> },
                    onReject = {},
                    onTagChanged = { _, _ -> },
                    onReportTag = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("No photos shown yet").assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = reviewState(listOf(photo("latest"), photo("older")))
        }

        composeRule.onNodeWithContentDescription("Photo: latest").assertIsDisplayed()
        composeRule.onAllNodesWithText("New photos available").assertCountEquals(0)
    }

    @Test
    fun newPhotoAtTopBecomesVisible() {
        val state = mutableStateOf(reviewState(listOf(photo("existing"))))
        composeRule.setContent {
            PhotoSlideAppTheme {
                PhotoReviewScreen(
                    state = state.value,
                    onOpenSettings = {},
                    onRate = { _, _ -> },
                    onReject = {},
                    onTagChanged = { _, _ -> },
                    onReportTag = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Photo: existing").assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = reviewState(listOf(photo("newest"), photo("existing")))
        }

        composeRule.onNodeWithContentDescription("Photo: newest").assertIsDisplayed()
        composeRule.onAllNodesWithText("New photos available").assertCountEquals(0)
    }

    @Test
    fun olderPhotoChangesDoNotShowNewPhotosAction() {
        val state = mutableStateOf(reviewState(listOf(photo("latest"), photo("older"))))
        composeRule.setContent {
            PhotoSlideAppTheme {
                PhotoReviewScreen(
                    state = state.value,
                    onOpenSettings = {},
                    onRate = { _, _ -> },
                    onReject = {},
                    onTagChanged = { _, _ -> },
                    onReportTag = { _, _ -> },
                )
            }
        }

        composeRule.runOnIdle {
            state.value = reviewState(listOf(photo("latest"), photo("older"), photo("even-older")))
        }

        composeRule.onNodeWithContentDescription("Photo: latest").assertIsDisplayed()
        composeRule.onAllNodesWithText("New photos available").assertCountEquals(0)
    }

    @Test
    fun newPhotoWhileBrowsingCanBeOpenedFromAffordance() {
        val initialPhotos = listOf("latest", "photo-2", "photo-3", "photo-4", "photo-5")
            .map(::photo)
        val state = mutableStateOf(reviewState(initialPhotos))
        composeRule.setContent {
            PhotoSlideAppTheme {
                PhotoReviewScreen(
                    state = state.value,
                    onOpenSettings = {},
                    onRate = { _, _ -> },
                    onReject = {},
                    onTagChanged = { _, _ -> },
                    onReportTag = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("photo-review-list")
            .performScrollToNode(hasContentDescription("Photo: photo-4"))
        composeRule.onNodeWithContentDescription("Photo: photo-4").assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = reviewState(listOf(photo("newest")) + initialPhotos)
        }

        composeRule.onNodeWithContentDescription("Photo: photo-4").assertIsDisplayed()
        composeRule.onNodeWithText("New photos available").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Photo: newest").assertIsDisplayed()
        composeRule.onAllNodesWithText("New photos available").assertCountEquals(0)
    }

    @Test
    fun localNetworkPermissionNoticeOffersRecoveryWithoutHidingPhotos() {
        val state = mutableStateOf(reviewState(listOf(photo("latest"))))
        var permissionRequested = false
        var settingsOpened = false
        composeRule.setContent {
            PhotoSlideAppTheme {
                PhotoReviewScreen(
                    state = state.value,
                    onOpenSettings = {},
                    onRate = { _, _ -> },
                    onReject = {},
                    onTagChanged = { _, _ -> },
                    onReportTag = { _, _ -> },
                    localNetworkPermissionGranted = false,
                    onRequestLocalNetworkPermission = { permissionRequested = true },
                    onOpenAppSettings = { settingsOpened = true },
                )
            }
        }

        composeRule.onNodeWithText("Local network access is off").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Photo: latest").assertIsDisplayed()
        composeRule.onNodeWithText("Request permission").performClick()
        composeRule.runOnIdle { assert(permissionRequested) }
        composeRule.onNodeWithText("Open app settings").performClick()
        composeRule.runOnIdle { assert(settingsOpened) }
    }

    private fun reviewState(photos: List<Photo>) = PhotoReviewUiState(
        photos = photos,
        connection = PhotoConnection.CONNECTED,
        serverAddress = "http://server.test:8080",
    )

    private fun photo(name: String) = Photo(
        path = name,
        album = null,
        metadata = PhotoMetadata(null, null, null, emptyMap()),
        imageUrl = "https://server.test/$name.jpg",
        shownAt = "2026-11-08T10:00:00Z",
    )
}