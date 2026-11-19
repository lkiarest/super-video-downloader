package com.myAllVideoBrowser.ui.main.audio

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

class AudioFragment : BaseFragment() {
    companion object {
        const val SOURCE_REQUEST = "audio_source"
    }

    private var binding: FragmentAudioBinding? = null
    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var exportName: String? = null
    private var currentWork: WorkInfo? = null
    private var enqueuing = false
    private var refreshJob: Job? = null
    private lateinit var audioAdapter: AudioAdapter
    private val workManager get() = WorkManager.getInstance(requireContext())
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
                    toast(R.string.audio_saved)
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
                    ?.filter { it.isFile && it.extension in listOf("mp3", "m4a") }
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
                            .setMessage(getString(R.string.audio_delete_message, file.name))
                            .setNegativeButton(R.string.all_text_cancel, null)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val deleted = withContext(Dispatchers.IO) { file.delete() }
                                    if (!deleted) toast(R.string.audio_file_error)
                                    refreshFiles()
                                }
                            }.show()
                    }
                } catch (_: Exception) {
                    toast(R.string.audio_file_error)
                }
                true
            }
            show()
        }
    }

    private fun mimeType(file: File) = if (file.extension == "mp3") "audio/mpeg" else "audio/mp4"

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
            holder.ui.audioName.text = file.name
            holder.ui.audioDetails.text = "${file.extension.uppercase()} · ${Formatter.formatShortFileSize(holder.itemView.context, file.length())} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified()))}"
            holder.ui.root.contentDescription = getString(R.string.audio_play, file.name)
            holder.ui.root.setOnClickListener {
                startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerFragment.VIDEO_URL, Uri.fromFile(file).toString())
                    putExtra(VideoPlayerFragment.VIDEO_NAME, file.name)
                })
            }
            holder.ui.audioMenu.setOnClickListener { showAudioMenu(it, file) }
        }
    }
}
