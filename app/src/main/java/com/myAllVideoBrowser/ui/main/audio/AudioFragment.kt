package com.myAllVideoBrowser.ui.main.audio

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentAudioBinding
import com.myAllVideoBrowser.databinding.ItemAudioBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.util.concurrent.TimeUnit

class AudioFragment : BaseFragment() {
    companion object {
        const val SOURCE_REQUEST = "audio_source"
    }

    private var binding: FragmentAudioBinding? = null
    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var transcriptionUri: Uri? = null
    private var transcriptionName = ""
    private var exportName: String? = null
    private var currentWork: WorkInfo? = null
    private var transcriptionWork: WorkInfo? = null
    private var transcriptionEnqueuing = false
    private var authBusy = false
    private var transcriptionOwner: String? = null
    private var accountLabel = ""
    private var enqueuing = false
    private var refreshJob: Job? = null
    private lateinit var audioAdapter: AudioAdapter
    private val workManager get() = WorkManager.getInstance(requireContext())
    private val transcriptionBusy get() = transcriptionEnqueuing || transcriptionWork?.state?.isFinished == false
    private val busy get() = enqueuing || currentWork?.state?.isFinished == false

    private val selectVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = requireContext().contentResolver
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers only grant access for this app session.
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: "video"
                }
                chooseSource(uri, name)
            }
        }
    }

    private val selectAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = requireContext().contentResolver
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers only grant access for this app session.
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: "audio"
                }
                chooseTranscriptionSource(uri, name)
            }
        }
    }

    private val exportAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = exportName
        exportName = null
        val destination = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && destination != null && name != null) {
            val context = requireContext().applicationContext
            val source = File(AudioExtractionWorker.directory(context), File(name).name)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        source.inputStream().use { input ->
                            requireNotNull(context.contentResolver.openOutputStream(destination, "w")).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    toast(if (source.extension == "txt") R.string.transcription_saved else R.string.audio_saved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    toast(R.string.audio_file_error)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedUri = savedInstanceState?.getString("source_uri")?.let(Uri::parse)
        selectedName = savedInstanceState?.getString("source_name").orEmpty()
        exportName = savedInstanceState?.getString("export_name")
        transcriptionUri = savedInstanceState?.getString("transcription_uri")?.let(Uri::parse)
        transcriptionName = savedInstanceState?.getString("transcription_name").orEmpty()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentAudioBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ui = requireNotNull(binding)
        audioAdapter = AudioAdapter()
        ui.audioList.layoutManager = LinearLayoutManager(requireContext())
        ui.audioList.adapter = audioAdapter
        ui.selectVideo.setOnClickListener { selectVideo.launch(arrayOf("video/*")) }
        ui.selectAudio.setOnClickListener { selectAudio.launch(arrayOf("audio/*")) }
        ui.transcriptionAccount.setOnClickListener { showTranscriptionAccount() }
        ui.startTranscription.setOnClickListener { confirmTranscription() }
        ui.cancelTranscription.setOnClickListener { workManager.cancelUniqueWork(AudioTranscriptionWorker.WORK_NAME) }
        workManager.getWorkInfosForUniqueWorkLiveData(AudioTranscriptionWorker.WORK_NAME).observe(viewLifecycleOwner) { work ->
            val previous = transcriptionWork
            transcriptionWork = work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull()
            if (transcriptionWork != null) transcriptionEnqueuing = false
            renderTranscription()
            if (transcriptionWork?.state?.isFinished == true &&
                (previous?.id != transcriptionWork?.id || previous?.state != transcriptionWork?.state)) refreshFiles()
        }
        refreshTranscriptionAccount()
        ui.extractAudio.setOnClickListener { startExtraction() }
        ui.cancelExtraction.setOnClickListener { workManager.cancelUniqueWork(AudioExtractionWorker.WORK_NAME) }
        parentFragmentManager.setFragmentResultListener(SOURCE_REQUEST, viewLifecycleOwner) { _, source ->
            source.getString("uri")?.let { chooseSource(Uri.parse(it), source.getString("name") ?: "video") }
        }
        workManager.getWorkInfosForUniqueWorkLiveData(AudioExtractionWorker.WORK_NAME).observe(viewLifecycleOwner) { work ->
            val previous = currentWork
            currentWork = work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull()
            if (currentWork != null) enqueuing = false
            render()
            if (currentWork?.state?.isFinished == true && (previous?.id != currentWork?.id || previous?.state != currentWork?.state)) {
                refreshFiles()
            }
        }
        render()
        refreshFiles()
    }

    override fun onResume() {
        super.onResume()
        if (binding != null) refreshFiles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source_uri", selectedUri?.toString())
        outState.putString("source_name", selectedName)
        outState.putString("export_name", exportName)
        outState.putString("transcription_uri", transcriptionUri?.toString())
        outState.putString("transcription_name", transcriptionName)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        binding?.audioList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun chooseSource(uri: Uri, name: String) {
        if (busy) {
            toast(R.string.audio_busy)
            return
        }
        selectedUri = uri
        selectedName = name
        render()
    }

    private fun startExtraction() {
        val uri = selectedUri ?: return
        val ui = binding ?: return
        if (busy) return
        val format = if (ui.formatGroup.checkedButtonId == R.id.format_m4a) "m4a" else "mp3"
        val request = OneTimeWorkRequestBuilder<AudioExtractionWorker>().setInputData(workDataOf(
            AudioExtractionWorker.URI to uri.toString(),
            AudioExtractionWorker.NAME to selectedName,
            AudioExtractionWorker.FORMAT to format
        )).build()
        enqueuing = true
        render()
        val operation = workManager.enqueueUniqueWork(AudioExtractionWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        operation.result.addListener({
            try {
                operation.result.get()
            } catch (_: Exception) {
                enqueuing = false
                render()
                toast(R.string.audio_extract_error)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun render() {
        val ui = binding ?: return
        ui.sourceName.text = selectedName.ifBlank { getString(R.string.audio_no_selection) }
        ui.selectVideo.isEnabled = !busy
        ui.extractAudio.isEnabled = !busy && selectedUri != null
        ui.formatMp3.isEnabled = !busy
        ui.formatM4a.isEnabled = !busy
        ui.cancelExtraction.isVisible = busy
        val work = currentWork
        val progress = work?.progress?.getInt(AudioExtractionWorker.PROGRESS, -1) ?: -1
        ui.extractionProgress.visibility = View.INVISIBLE
        ui.extractionProgress.isIndeterminate = progress < 0
        if (progress >= 0) ui.extractionProgress.progress = progress
        ui.extractionProgress.isVisible = busy
        val message = when {
            enqueuing -> getString(R.string.audio_waiting)
            work?.state == WorkInfo.State.RUNNING -> if (progress >= 0) getString(R.string.audio_progress, progress) else getString(R.string.audio_extracting)
            work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED -> getString(R.string.audio_waiting)
            work?.state == WorkInfo.State.SUCCEEDED -> getString(R.string.audio_success)
            work?.state == WorkInfo.State.CANCELLED -> getString(R.string.audio_cancelled)
            work?.state == WorkInfo.State.FAILED -> getString(when (work.outputData.getString(AudioExtractionWorker.ERROR)) {
                "no_audio" -> R.string.audio_no_track
                "read" -> R.string.audio_read_error
                else -> R.string.audio_extract_error
            })
            else -> ""
        }
        ui.extractionStatus.text = message
        ui.extractionStatus.isVisible = message.isNotEmpty()
        ui.extractionStatus.setTextColor(MaterialColors.getColor(ui.root,
            if (work?.state == WorkInfo.State.FAILED) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun refreshFiles() {
        val context = context?.applicationContext ?: return
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                AudioExtractionWorker.directory(context).listFiles()
                    ?.filter { it.isFile && it.extension in listOf("mp3", "m4a", "txt") }
                    ?.sortedByDescending { it.lastModified() }.orEmpty()
            }
            binding?.let {
                audioAdapter.files = files
                audioAdapter.notifyDataSetChanged()
                it.emptyAudio.isVisible = files.isEmpty()
            }
        }
    }

    private fun showAudioMenu(anchor: View, file: File) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.video_menu_share)
            menu.add(0, 2, 1, R.string.audio_save)
            menu.add(0, 3, 2, R.string.delete)
            if (file.extension != "txt") menu.add(0, 4, 3, R.string.transcription_title)
            setOnMenuItemClickListener { item ->
                try {
                    when (item.itemId) {
                        1 -> {
                            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = mimeType(file)
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri(file.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, getString(R.string.video_menu_share)))
                        }
                        2 -> {
                            exportName = file.name
                            exportAudio.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = mimeType(file)
                                putExtra(Intent.EXTRA_TITLE, file.name)
                            })
                        }
                        3 -> MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.delete)
                            .setMessage(getString(if (file.extension == "txt") R.string.transcription_delete_message else R.string.audio_delete_message, file.name))
                            .setNegativeButton(R.string.all_text_cancel, null)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val deleted = withContext(Dispatchers.IO) { file.delete() }
                                    if (!deleted) toast(R.string.audio_file_error)
                                    refreshFiles()
                                }
                            }.show()
                        4 -> chooseTranscriptionSource(Uri.fromFile(file), file.name)
                    }
                } catch (_: Exception) {
                    toast(R.string.audio_file_error)
                }
                true
            }
            show()
        }
    }

    private fun chooseTranscriptionSource(uri: Uri, name: String) {
        if (transcriptionBusy) {
            toast(R.string.transcription_busy)
            return
        }
        transcriptionUri = uri
        transcriptionName = name
        renderTranscription()
        binding?.transcriptionSource?.let { source ->
            source.requestRectangleOnScreen(android.graphics.Rect(0, 0, source.width, source.height), false)
        }
    }

    private fun refreshTranscriptionAccount() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val account = WhisperApi(context).account()
                transcriptionOwner = account?.getString("id")
                accountLabel = account?.optString("username")?.takeIf { it.isNotBlank() }
                    ?: account?.optString("email").orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                transcriptionOwner = null
                accountLabel = ""
                toast(R.string.transcription_auth_error)
            }
            renderTranscription()
        }
    }

    private fun showTranscriptionAccount() {
        if (transcriptionBusy || authBusy) return
        val context = requireContext()
        val api = WhisperApi(context.applicationContext)
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        fun field(label: Int, type: Int): TextInputEditText {
            val input = TextInputEditText(context).apply {
                inputType = type
                isSingleLine = true
                isSaveEnabled = false
            }
            fields.addView(TextInputLayout(context).apply { hint = getString(label); addView(input) })
            return input
        }
        val username = field(R.string.transcription_username, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field(R.string.transcription_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.transcription_account)
            .setMessage(R.string.transcription_account_hint).setView(fields)
            .setPositiveButton(R.string.transcription_login, null)
            .setNegativeButton(R.string.all_text_cancel, null)
            .apply { if (transcriptionOwner != null) setNeutralButton(R.string.transcription_logout, null) }.create()
        dialog.setOnShowListener {
            fun submit(logout: Boolean) {
                if (authBusy) return
                if (!logout && (username.text.isNullOrBlank() || password.text.isNullOrEmpty())) {
                    password.error = getString(R.string.transcription_credentials_required)
                    return
                }
                val user = username.text.toString()
                val secret = password.text.toString()
                authBusy = true
                renderTranscription()
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
                dialog.setCancelable(false)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (logout) api.logout() else api.login(user, secret)
                        password.text?.clear()
                        dialog.dismiss()
                        refreshTranscriptionAccount()
                    } catch (cancelled: CancellationException) {
                        dialog.dismiss()
                        throw cancelled
                    } catch (error: Exception) {
                        password.error = getString(when {
                            error is WhisperApi.ApiException && error.code == "password_change_required" -> R.string.transcription_password_change
                            error is WhisperApi.ApiException && error.status == 429 -> R.string.transcription_rate_limit
                            error is WhisperApi.ApiException && error.status == 401 -> R.string.transcription_invalid_credentials
                            else -> R.string.transcription_auth_error
                        })
                    } finally {
                        authBusy = false
                        renderTranscription()
                        dialog.setCancelable(true)
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit(false) }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { submit(true) }
        }
        dialog.show()
    }

    private fun confirmTranscription() {
        if (transcriptionBusy || transcriptionUri == null || authBusy) return
        if (transcriptionOwner == null) {
            showTranscriptionAccount()
            return
        }
        val prefs = requireContext().getSharedPreferences("transcription", Context.MODE_PRIVATE)
        if (prefs.getString("consent", null) == WhisperApi.CONSENT) {
            startTranscription()
        } else {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.transcription_title)
                .setMessage(R.string.transcription_consent)
                .setNegativeButton(R.string.all_text_cancel, null)
                .setPositiveButton(R.string.transcription_agree) { _, _ ->
                    prefs.edit().putString("consent", WhisperApi.CONSENT).apply()
                    startTranscription()
                }.show()
        }
    }

    private fun startTranscription() {
        val uri = transcriptionUri ?: return
        val owner = transcriptionOwner ?: return
        val ui = binding ?: return
        if (transcriptionBusy) return
        val language = if (ui.transcriptionLanguageGroup.checkedButtonId == R.id.transcription_english) "en" else "zh"
        val request = OneTimeWorkRequestBuilder<AudioTranscriptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(AudioTranscriptionWorker.URI to uri.toString(),
                AudioTranscriptionWorker.NAME to transcriptionName.take(255), AudioTranscriptionWorker.OWNER to owner,
                AudioTranscriptionWorker.LANGUAGE to language, AudioTranscriptionWorker.CREATED_AT to Instant.now().toString())).build()
        transcriptionEnqueuing = true
        renderTranscription()
        val operation = workManager.enqueueUniqueWork(AudioTranscriptionWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        operation.result.addListener({
            try {
                operation.result.get()
            } catch (_: Exception) {
                transcriptionEnqueuing = false
                renderTranscription()
                toast(R.string.transcription_error)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun renderTranscription() {
        val ui = binding ?: return
        ui.transcriptionSource.text = transcriptionName.ifBlank { getString(R.string.transcription_no_selection) }
        ui.transcriptionAccount.text = if (transcriptionOwner == null) getString(R.string.transcription_login)
            else getString(R.string.transcription_logged_in, accountLabel)
        ui.transcriptionAccount.isEnabled = !transcriptionBusy && !authBusy
        ui.selectAudio.isEnabled = !transcriptionBusy
        ui.startTranscription.isEnabled = !transcriptionBusy && !authBusy && transcriptionUri != null
        ui.transcriptionChinese.isEnabled = !transcriptionBusy
        ui.transcriptionEnglish.isEnabled = !transcriptionBusy
        ui.cancelTranscription.isVisible = transcriptionBusy
        val work = transcriptionWork
        val progress = work?.progress?.getInt(AudioTranscriptionWorker.PROGRESS, -1) ?: -1
        ui.transcriptionProgress.visibility = View.INVISIBLE
        ui.transcriptionProgress.isIndeterminate = progress < 0
        if (progress >= 0) ui.transcriptionProgress.progress = progress
        ui.transcriptionProgress.isVisible = transcriptionBusy
        val message = when {
            transcriptionEnqueuing -> getString(R.string.transcription_waiting)
            work?.state == WorkInfo.State.RUNNING -> when (work.progress.getString(AudioTranscriptionWorker.STAGE)) {
                "decode" -> getString(R.string.transcription_decode)
                "retry" -> getString(R.string.transcription_retry)
                else -> if (progress >= 0) getString(R.string.transcription_progress, progress) else getString(R.string.transcription_running)
            }
            work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED -> getString(
                if (work.runAttemptCount > 0) R.string.transcription_retry else R.string.transcription_waiting)
            work?.state == WorkInfo.State.SUCCEEDED -> getString(R.string.transcription_success)
            work?.state == WorkInfo.State.CANCELLED -> getString(R.string.transcription_cancelled)
            work?.state == WorkInfo.State.FAILED -> getString(when (work.outputData.getString(AudioTranscriptionWorker.ERROR)) {
                "decode" -> R.string.transcription_decode_error
                "no_speech" -> R.string.transcription_no_speech
                "auth" -> R.string.transcription_login_required
                "quota" -> R.string.transcription_quota
                "forbidden" -> R.string.transcription_forbidden
                "network" -> R.string.transcription_network_error
                "server" -> R.string.transcription_server_error
                else -> R.string.transcription_error
            })
            else -> ""
        }
        ui.transcriptionStatus.text = message
        ui.transcriptionStatus.isVisible = message.isNotEmpty()
        ui.transcriptionStatus.setTextColor(MaterialColors.getColor(ui.root,
            if (work?.state == WorkInfo.State.FAILED) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun showTranscript(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        val chars = CharArray(100_001)
                        var size = 0
                        while (size < chars.size) {
                            val count = reader.read(chars, size, chars.size - size)
                            if (count < 0) break
                            size += count
                        }
                        String(chars, 0, size)
                    }
                }
                val truncated = preview.length > 100_000
                val text = preview.take(100_000)
                val context = requireContext()
                val textView = TextView(context).apply {
                    this.text = if (truncated) "$text\n\n${getString(R.string.transcription_preview_limit)}" else text
                    setTextIsSelectable(true)
                    val padding = (24 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(file.name)
                    .setView(ScrollView(context).apply { addView(textView) })
                    .setPositiveButton(android.R.string.ok, null)
                    .apply {
                        if (!truncated) setNeutralButton(R.string.transcription_copy) { _, _ ->
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText(file.name, text))
                            toast(R.string.transcription_copied)
                        }
                    }.show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.audio_file_error)
            }
        }
    }

    private fun mimeType(file: File) = when (file.extension) {
        "txt" -> "text/plain"
        "mp3" -> "audio/mpeg"
        else -> "audio/mp4"
    }

    private fun toast(message: Int) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    private inner class AudioAdapter : RecyclerView.Adapter<AudioAdapter.Holder>() {
        var files: List<File> = emptyList()
        inner class Holder(val ui: ItemAudioBinding) : RecyclerView.ViewHolder(ui.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemAudioBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = files.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = files[position]
            holder.ui.audioIcon.setImageResource(if (file.extension == "txt") android.R.drawable.ic_menu_edit else R.drawable.play_circle_24px)
            holder.ui.audioName.text = file.name
            holder.ui.audioDetails.text = "${file.extension.uppercase()} · ${Formatter.formatShortFileSize(holder.itemView.context, file.length())} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified()))}"
            holder.ui.root.contentDescription = getString(if (file.extension == "txt") R.string.transcription_view else R.string.audio_play, file.name)
            holder.ui.root.setOnClickListener {
                if (file.extension == "txt") {
                    showTranscript(file)
                    return@setOnClickListener
                }
                startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerFragment.VIDEO_URL, Uri.fromFile(file).toString())
                    putExtra(VideoPlayerFragment.VIDEO_NAME, file.name)
                })
            }
            holder.ui.audioMenu.setOnClickListener { showAudioMenu(it, file) }
        }
    }
}
