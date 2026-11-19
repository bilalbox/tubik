package com.github.libretube.ui.dialogs

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.updater.AppUpdater
import com.github.libretube.updater.DownloadAndInstallOutcome
import com.github.libretube.updater.DownloadAndInstallStage
import com.github.libretube.updater.UpdateCheckResult
import com.github.libretube.updater.UpdateInstallStatus
import com.github.libretube.updater.UpdateManifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin Settings dialog presenting [AppUpdater] check results, download/verification progress, and
 * install outcomes without duplicating OTA security orchestration.
 */
class AppUpdateDialog(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val scope: CoroutineScope,
    private val onStateUpdated: () -> Unit,
    private val updater: AppUpdater = AppUpdater.getInstance(context),
) {
    private var activeJob: Job? = null
    private var installStatusJob: Job? = null
    private var availableManifest: UpdateManifest? = null

    fun show() {
        val container = createDialogContainer(context)
        val statusView = TextView(context)
        val detailsView =
            TextView(context).apply {
                setPadding(0, SECTION_GAP_DP.toFloat().dpToPx(), 0, 0)
                isVisible = false
            }
        val progressBar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isVisible = false
                setPadding(0, SECTION_GAP_DP.toFloat().dpToPx(), 0, 0)
            }
        container.addView(statusView)
        container.addView(detailsView)
        container.addView(progressBar)

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_updates)
                .setView(container)
                .setPositiveButton(R.string.update_check_now, null)
                .setNeutralButton(R.string.update_view_release_notes, null)
                .setNegativeButton(R.string.update_close, null)
                .create()

        dialog.setOnDismissListener {
            activeJob?.cancel()
            installStatusJob?.cancel()
        }

        dialog.setOnShowListener {
            val views = DialogViews(dialog, statusView, detailsView, progressBar)
            bindInstallEvents(views)
            val cached = updater.cachedState().result
            if (cached != null) {
                renderCheckResult(views, cached)
            } else {
                views.statusView.setText(R.string.update_checking)
                renderActionButtons(views)
            }
            startCheck(views)
        }

        dialog.show()
    }

    private fun bindInstallEvents(views: DialogViews) {
        installStatusJob = scope.launch {
            updater.installEvents.collectLatest { event ->
                views.progressBar.isVisible = false
                when (event) {
                    UpdateInstallStatus.BlockedByScreenPin -> {
                        views.statusView.setText(R.string.update_unpin_before_install)
                    }
                    UpdateInstallStatus.Succeeded -> {
                        views.statusView.setText(R.string.update_install_success)
                        views.dialog.dismiss()
                    }
                    UpdateInstallStatus.Aborted -> renderActionButtons(views)
                    is UpdateInstallStatus.Failed -> {
                        views.statusView.text =
                            context.getString(R.string.update_install_failed, event.message ?: "")
                        renderActionButtons(views)
                    }
                }
            }
        }
    }

    private fun renderCheckResult(views: DialogViews, result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                val manifest = result.manifest
                availableManifest = manifest
                views.statusView.text =
                    context.getString(
                        R.string.update_available_header,
                        manifest.versionName,
                        BuildConfig.VERSION_NAME,
                    )
                views.detailsView.text = buildReleaseDetails(context, manifest)
                views.detailsView.isVisible = true
            }
            is UpdateCheckResult.UpToDate -> {
                availableManifest = null
                views.statusView.text =
                    context.getString(R.string.update_summary_up_to_date, BuildConfig.VERSION_NAME)
                views.detailsView.isVisible = false
            }
            is UpdateCheckResult.IncompatibleSdk -> {
                availableManifest = null
                views.statusView.text =
                    context.getString(
                        R.string.update_summary_incompatible,
                        BuildConfig.VERSION_NAME,
                        result.manifest.versionName,
                        result.manifest.minSdk,
                    )
                views.detailsView.isVisible = false
            }
            is UpdateCheckResult.InvalidManifest -> {
                availableManifest = null
                views.statusView.text =
                    context.getString(R.string.update_check_failed, result.reason)
                views.detailsView.isVisible = false
            }
        }
        renderActionButtons(views)
    }

    private fun renderActionButtons(views: DialogViews) {
        val manifest = availableManifest
        val positive = views.dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val neutral = views.dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        if (manifest != null) {
            positive.setText(R.string.update_download_and_install)
            positive.isEnabled = true
            positive.setOnClickListener { beginDownloadAndInstall(views, manifest) }

            val notesUrl = manifest.releaseNotesUrl
            val hasHttpsNotes = UpdateManifest.isHttpsUrl(notesUrl)
            neutral.isVisible = hasHttpsNotes
            if (hasHttpsNotes) {
                neutral.setOnClickListener {
                    if (updater.canOpenExternalLink()) {
                        IntentHelper.openLinkFromHref(context, fragmentManager, notesUrl)
                    } else {
                        views.statusView.setText(R.string.update_unpin_before_install)
                    }
                }
            }
        } else {
            positive.setText(R.string.update_check_now)
            positive.isEnabled = true
            positive.setOnClickListener { startCheck(views) }
            neutral.isVisible = false
        }
    }

    private fun beginDownloadAndInstall(views: DialogViews, manifest: UpdateManifest) {
        val positive = views.dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false
        views.progressBar.isVisible = true
        views.progressBar.isIndeterminate = false
        views.progressBar.max = PERCENT_SCALE
        views.progressBar.progress = 0
        views.statusView.setText(R.string.update_downloading)

        activeJob?.cancel()
        activeJob = scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    updater.downloadAndInstall(manifest) { stage ->
                        views.progressBar.post { renderStage(views, stage) }
                    }
                }
            renderInstallOutcome(views, positive, outcome)
        }
    }

    private fun renderStage(views: DialogViews, stage: DownloadAndInstallStage) {
        when (stage) {
            is DownloadAndInstallStage.Downloading -> {
                if (stage.totalBytes > 0L) {
                    val percent =
                        ((stage.bytesRead * PERCENT_SCALE) / stage.totalBytes)
                            .toInt()
                            .coerceIn(0, PERCENT_SCALE)
                    views.progressBar.isIndeterminate = false
                    views.progressBar.progress = percent
                    views.statusView.text =
                        context.getString(R.string.update_downloading_progress, percent)
                }
            }
            DownloadAndInstallStage.Verifying -> {
                views.progressBar.isIndeterminate = true
                views.statusView.setText(R.string.update_verifying)
            }
            DownloadAndInstallStage.Installing -> {
                views.progressBar.isIndeterminate = true
                views.statusView.setText(R.string.update_install_starting)
            }
        }
    }

    private fun renderInstallOutcome(
        views: DialogViews,
        positive: android.widget.Button,
        outcome: DownloadAndInstallOutcome,
    ) {
        if (outcome == DownloadAndInstallOutcome.InstallCommitted) return
        views.progressBar.isVisible = false
        positive.isEnabled = true
        when (outcome) {
            DownloadAndInstallOutcome.BlockedByScreenPin ->
                views.statusView.setText(R.string.update_unpin_before_install)
            DownloadAndInstallOutcome.NeedsUnknownSourcesPermission ->
                showUnknownSourcesGuideDialog(views.statusView)
            is DownloadAndInstallOutcome.DownloadFailed ->
                views.statusView.text =
                    context.getString(
                        R.string.update_download_failed,
                        outcome.error.displayMessage(),
                    )
            is DownloadAndInstallOutcome.VerificationFailed ->
                views.statusView.setText(R.string.update_verification_failed)
            is DownloadAndInstallOutcome.InstallFailed ->
                views.statusView.text =
                    context.getString(
                        R.string.update_install_failed,
                        outcome.error.displayMessage(),
                    )
            DownloadAndInstallOutcome.InstallCommitted -> Unit
        }
    }

    private fun startCheck(views: DialogViews) {
        activeJob?.cancel()
        val positive = views.dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false
        views.progressBar.isVisible = true
        views.progressBar.isIndeterminate = true
        if (availableManifest == null) {
            views.statusView.setText(R.string.update_checking)
        }

        activeJob = scope.launch {
            val result = withContext(Dispatchers.IO) { updater.checkForUpdate() }
            views.progressBar.isVisible = false
            positive.isEnabled = true
            onStateUpdated()

            result
                .onSuccess { checkResult -> renderCheckResult(views, checkResult) }
                .onFailure { error ->
                    views.statusView.text =
                        context.getString(R.string.update_check_failed, error.displayMessage())
                    renderActionButtons(views)
                }
        }
    }

    private fun showUnknownSourcesGuideDialog(statusView: TextView) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_unknown_sources_title)
            .setMessage(R.string.update_unknown_sources_message)
            .setPositiveButton(R.string.update_open_install_permission) { _, _ ->
                if (updater.canOpenExternalLink()) {
                    val intent =
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri(),
                        )
                    context.startActivity(intent)
                } else {
                    statusView.setText(R.string.update_unpin_before_install)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class DialogViews(
        val dialog: AlertDialog,
        val statusView: TextView,
        val detailsView: TextView,
        val progressBar: ProgressBar,
    )

    private fun Throwable.displayMessage(): String = localizedMessage ?: javaClass.simpleName

    companion object {
        private const val CONTAINER_PADDING_DP = 24
        private const val CONTAINER_TOP_PADDING_DP = 12
        private const val SECTION_GAP_DP = 12
        private const val PERCENT_SCALE = 100
        private const val BYTES_PER_MIB = 1024.0 * 1024.0

        private fun createDialogContainer(context: Context): LinearLayout {
            val horizontal = CONTAINER_PADDING_DP.toFloat().dpToPx()
            val vertical = CONTAINER_TOP_PADDING_DP.toFloat().dpToPx()
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(horizontal, vertical, horizontal, 0)
            }
        }

        private fun buildReleaseDetails(context: Context, manifest: UpdateManifest): String {
            val lines = mutableListOf<String>()
            if (manifest.sizeBytes > 0L) {
                val sizeMib = manifest.sizeBytes / BYTES_PER_MIB
                lines.add(context.getString(R.string.update_size_mb, sizeMib))
            }
            lines.add(context.getString(R.string.update_release_notes_label))
            lines.add(
                manifest.changelog.ifBlank { context.getString(R.string.update_no_release_notes) },
            )
            return lines.joinToString("\n")
        }
    }
}
