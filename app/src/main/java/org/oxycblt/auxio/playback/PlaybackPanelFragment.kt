/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.lyrics.LrcParser
import org.oxycblt.auxio.playback.lyrics.LyricLine
import org.oxycblt.auxio.playback.lyrics.LyricsStore
import org.oxycblt.auxio.playback.queue.QueueViewModel
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.swiper.CarouselTransformer
import org.oxycblt.auxio.playback.ui.swiper.CoverPagerAdapter
import org.oxycblt.auxio.playback.ui.swiper.UserAwarePagerCallback
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.recycler
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.smoothScrollByPageTo
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener {
    private val coverPagerAdapter = CoverPagerAdapter(::showLyricsScreen)
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val queueModel: QueueViewModel by viewModels()
    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var lyricsLauncher: ActivityResultLauncher<Array<String>>? = null
    private var lyricsFolderLauncher: ActivityResultLauncher<Uri?>? = null
    private val lyricsStore by lazy(LazyThreadSafetyMode.NONE) { LyricsStore(requireContext()) }
    private var lyricsContainer: LinearLayout? = null
    private var lyricsText: TextView? = null
    private var lyricsScroll: ScrollView? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private var activeLyricIndex = -1
    private var lyricsSongUri: Uri? = null
    private var embeddedLyricsText: String? = null
    private var showEmbeddedLyrics = false
    private var lyricsSong: Song? = null
    // Invalidates older automatic/manual loads when a newer request starts or the view is
    // destroyed.
    private var lyricsLoadGeneration = 0L
    private var userAwarePagerCallback: UserAwarePagerCallback? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // AudioEffect expects you to use startActivityForResult with the panel intent. There is no
        // contract analogue for this intent, so the generic contract is used instead.
        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Nothing to do
            }

        lyricsLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) loadLyrics(uri)
            }

        lyricsFolderLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) setLyricsFolder(uri)
            }

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        binding.playbackPager?.apply {
            // intentional LTR override since thepager is a chronological element
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            adapter = coverPagerAdapter
            userAwarePagerCallback =
                UserAwarePagerCallback(this) {
                        // Posting the queue goto command prevents the seekbar pos from desyncing
                        // from the song's duration, which creates a visual flicker in the seekbar.
                        post { queueModel.goto(it) }
                    }
                    .also { it.attach() }
            setPageTransformer(CarouselTransformer())
            recycler().apply {
                // Make it possible to collapse the bottom sheet from the ViewPager's touch area.
                isNestedScrollingEnabled = false
                // Visual effect consistency
                // TODO: Custom overscroll?
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            // Make it easier to collapse the bottom sheet
            dampen()
            offscreenPageLimit = 1
        }

        // Set up fast seek overlay
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.apply {
            @SuppressLint("RestrictedApi")
            setCornerSpringForce(
                SpringForce().apply {
                    stiffness = 700f
                    dampingRatio = 0.9f
                }
            )
            setOnClickListener { playbackModel.togglePlaying() }
        }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.pagerQueue, ::updatePager)
    }

    // FIXME: Old code!! Maybe not necessary anymore?
    //    override fun onStart() {
    //        super.onStart()
    //        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
    //        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    //    }

    //    override fun onStop() {
    //        super.onStop()
    //        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    //    }

    //    override fun onGlobalLayout() {
    //        if (binding == null || lastCoverWidth < 0) {
    //            return
    //        }
    // Hacky workaround for cover radius not being preserved in between sizing changes
    // (i.e split screen or landscape mode)
    // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
    // size to radically change, so we wait until it stabilizes and then force an image
    // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
    // did accidentally load the wrong image (with weird corner radius intended for bigger
    // covers) we can force it to reload.
    // If this breaks, it's fine since we also started a load as we normally did w/state
    // updates, so the cover will not break.
    //        val binding = requireBinding()
    //        val coverWidth = binding.playbackCover.width
    //        if (lastCoverWidth != coverWidth) {
    //            lastCoverWidth = coverWidth
    //        } else {
    //            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
    //            lastCoverWidth = -1
    //        }
    //    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        equalizerLauncher = null
        lyricsLauncher = null
        lyricsFolderLauncher = null
        lyricsContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        lyricsLoadGeneration++
        lyricsContainer = null
        lyricsText = null
        lyricsScroll = null
        lyricLines = emptyList()
        activeLyricIndex = -1
        // Reset lyric state so it is restored when the view is recreated.
        lyricsSongUri = null
        lyricsSong = null
        embeddedLyricsText = null
        showEmbeddedLyrics = false
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        userAwarePagerCallback?.release()
        binding.playbackPager?.adapter = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_open_equalizer) {
            // Launch the system equalizer app, if possible.
            L.d("Launching equalizer")
            val equalizerIntent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    // Provide audio session ID so the equalizer can show options for this app
                    // in particular.
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                    // Signal music type so that the equalizer settings are appropriate for
                    // music playback.
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                requireNotNull(equalizerLauncher) { "Equalizer panel launcher was not available" }
                    .launch(equalizerIntent)
            } catch (e: ActivityNotFoundException) {
                requireContext().showToast(R.string.err_no_app)
            }
            return true
        }

        return false
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        if (lyricsSongUri != song.uri) {
            lyricsSongUri = song.uri
            lyricLines = emptyList()
            activeLyricIndex = -1
            embeddedLyricsText = song.lyrics?.trim()?.takeIf { it.isNotEmpty() }
            showEmbeddedLyrics = false
            lyricsSong = song
            renderLyricsSource()
            autoLoadLyrics(song)
        }
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
        updateLyrics(positionDs * 100L)
    }

    /** Opens an in-player lyrics surface; playback controls remain below it. */
    private fun showLyricsScreen() {
        if (lyricsContainer != null) return
        val binding = requireBinding()
        val context = requireContext()
        val root = binding.root as? ConstraintLayout ?: return

        binding.playbackPager?.visibility = View.INVISIBLE
        binding.playbackInfoContainerVisibility(false)

        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
                setBackgroundColor(android.graphics.Color.rgb(12, 9, 22))
                alpha = 0f
                translationY = 28f
            }
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val close =
            Button(context).apply {
                text = "×"
                textSize = 28f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = "Cerrar letras y volver a la portada"
                setOnClickListener { hideLyricsScreen() }
            }
        val title =
            TextView(context).apply {
                text = "LETRA"
                textSize = 13f
                letterSpacing = 0.18f
                setTextColor(android.graphics.Color.LTGRAY)
                gravity = Gravity.CENTER_VERTICAL
            }
        val source =
            Button(context).apply {
                text = "⌄"
                contentDescription = "Elegir fuente de letras"
                setTextColor(android.graphics.Color.rgb(205, 170, 255))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { chooseLyricsSource() }
            }
        val edit =
            Button(context).apply {
                text = "✎"
                contentDescription = "Gestionar letras"
                setTextColor(android.graphics.Color.rgb(205, 170, 255))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { manageLyrics() }
            }
        header.addView(close, LinearLayout.LayoutParams(48, 52))
        header.addView(title, LinearLayout.LayoutParams(0, 52, 1f))
        header.addView(source, LinearLayout.LayoutParams(44, 52))
        header.addView(edit, LinearLayout.LayoutParams(44, 52))
        panel.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val text =
            TextView(context).apply {
                textSize = 25f
                setLineSpacing(12f, 1.05f)
                setPadding(8, 32, 8, 48)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(android.graphics.Color.rgb(170, 160, 185))
            }
        val scroll =
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    text,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        lyricsText = text
        lyricsScroll = scroll
        lyricsContainer = panel

        val params =
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = R.id.playback_toolbar
                bottomToTop = R.id.playback_seek_bar
            }
        root.addView(panel, params)
        panel.bringToFront()
        panel.animate().alpha(1f).translationY(0f).setDuration(240L).start()
        renderLyricsSource()
    }

    private fun hideLyricsScreen() {
        val panel = lyricsContainer ?: return
        panel
            .animate()
            .alpha(0f)
            .translationY(24f)
            .setDuration(180L)
            .withEndAction {
                // The fragment view may be destroyed while this exit animation is running.
                if (lyricsContainer !== panel) return@withEndAction
                val currentBinding = binding ?: return@withEndAction
                (panel.parent as? ViewGroup)?.removeView(panel)
                lyricsContainer = null
                lyricsText = null
                lyricsScroll = null
                currentBinding.playbackPager?.visibility = View.VISIBLE
                currentBinding.playbackInfoContainerVisibility(true)
            }
            .start()
    }

    private fun openLyricsPicker() {
        lyricsLauncher?.launch(arrayOf("text/*", "application/octet-stream"))
    }

    private fun loadLyrics(uri: Uri) {
        val songUri = lyricsSongUri
        val loadGeneration = ++lyricsLoadGeneration
        val store = lyricsStore
        viewLifecycleOwner.lifecycleScope.launch {
            val parsed =
                withContext(Dispatchers.IO) {
                    val text = store.read(uri)
                    if (text == null) emptyList<LyricLine>() else LrcParser.parse(text)
                }
            if (lyricsSongUri != songUri || lyricsLoadGeneration != loadGeneration) return@launch
            if (parsed.isEmpty()) {
                toast("No se encontró una letra LRC válida.")
                return@launch
            }
            if (songUri != null) {
                runCatching {
                    requireContext()
                        .contentResolver
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                store.assign(songUri, uri)
            }
            applyLyrics(parsed, openScreen = true)
        }
    }

    /**
     * Finds the assigned LRC first, then a matching file in the selected folder. File access runs
     * off the main thread and stale results are ignored.
     */
    private fun autoLoadLyrics(song: Song) {
        val context = requireContext()
        val songUri = song.uri
        val loadGeneration = ++lyricsLoadGeneration
        val store = lyricsStore
        val title = song.name.resolve(context)
        val candidates =
            listOfNotNull(
                song.path.name?.substringBeforeLast('.'),
                title,
                "${song.artists.resolveNames(context)} - $title",
            )
        viewLifecycleOwner.lifecycleScope.launch {
            val text =
                withContext(Dispatchers.IO) {
                    store.assigned(songUri)?.let { store.read(it) }
                        ?: store.findInFolder(candidates)?.let { store.read(it) }
                }
            val parsed = if (text == null) emptyList<LyricLine>() else LrcParser.parse(text)
            if (
                parsed.isNotEmpty() &&
                    lyricsSongUri == songUri &&
                    lyricsLoadGeneration == loadGeneration
            ) {
                applyLyrics(parsed, openScreen = false)
            }
        }
    }

    private fun applyLyrics(parsed: List<LyricLine>, openScreen: Boolean) {
        lyricLines = parsed
        activeLyricIndex = -1
        showEmbeddedLyrics = false
        if (openScreen && lyricsContainer == null) showLyricsScreen()
        renderLyricsSource()
        updateLyrics(playbackModel.positionDs.value * 100L)
    }

    private fun setLyricsFolder(uri: Uri) {
        runCatching {
            requireContext()
                .contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        lyricsStore.folder = uri
        toast("Carpeta de letras guardada.")
        lyricsSong?.let { autoLoadLyrics(it) }
    }

    private fun clearAssignedLyrics() {
        val songUri = lyricsSongUri ?: return
        lyricsStore.unassign(songUri)
        lyricLines = emptyList()
        activeLyricIndex = -1
        renderLyricsSource()
        toast("Letra asociada eliminada.")
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    private fun chooseLyricsSource() {
        val choices = mutableListOf<String>()
        if (lyricLines.isNotEmpty()) choices.add("Letra sincronizada (.LRC)")
        if (embeddedLyricsText != null) choices.add("Letra incrustada en la canción")
        if (choices.isEmpty()) {
            toast("No hay letras disponibles.")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Fuente de letras")
            .setItems(choices.toTypedArray()) { _, index ->
                showEmbeddedLyrics = choices[index].startsWith("Letra incrustada")
                renderLyricsSource()
                if (!showEmbeddedLyrics) updateLyrics(playbackModel.positionDs.value * 100L)
            }
            .show()
    }

    private class LyricsAction(val label: String, val action: () -> Unit)

    private fun manageLyrics() {
        val actions = mutableListOf<LyricsAction>()
        actions.add(LyricsAction("Cargar archivo .LRC para esta canción") { openLyricsPicker() })
        actions.add(
            LyricsAction("Elegir carpeta de letras (búsqueda automática)") {
                lyricsFolderLauncher?.launch(null)
            }
        )
        val songUri = lyricsSongUri
        if (songUri != null && lyricsStore.assigned(songUri) != null) {
            actions.add(
                LyricsAction("Quitar el .LRC asociado a esta canción") { clearAssignedLyrics() }
            )
        }
        if (lyricsStore.folder != null) {
            actions.add(
                LyricsAction("Dejar de usar la carpeta de letras") {
                    lyricsStore.folder = null
                    toast("Carpeta de letras desactivada.")
                }
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Gestionar letras")
            .setItems(actions.map { it.label }.toTypedArray()) { _, index ->
                actions[index].action()
            }
            .show()
    }

    private fun renderLyricsSource() {
        val view = lyricsText ?: return
        when {
            !showEmbeddedLyrics && lyricLines.isNotEmpty() -> renderLyrics(activeLyricIndex)
            embeddedLyricsText != null -> {
                view.text = embeddedLyricsText
                view.setTextColor(android.graphics.Color.rgb(220, 212, 235))
            }
            lyricLines.isNotEmpty() -> renderLyrics(activeLyricIndex)
            else -> {
                view.text =
                    "Todavía no hay letra para esta canción.\n\nToca ✎ para cargar un archivo .LRC o elegir una carpeta de letras."
                view.setTextColor(android.graphics.Color.LTGRAY)
            }
        }
    }

    private fun updateLyrics(positionMs: Long) {
        if (showEmbeddedLyrics || lyricLines.isEmpty() || lyricsText == null) return
        val index = lyricLines.indexOfLast { it.timeMs <= positionMs }
        if (index != activeLyricIndex) {
            activeLyricIndex = index
            renderLyrics(index)
        }
    }

    private fun renderLyrics(activeIndex: Int) {
        val view = lyricsText ?: return
        val fullText = lyricLines.joinToString("\n") { it.text }
        val start =
            if (activeIndex >= 0) {
                lyricLines.take(activeIndex).sumOf { it.text.length + 1 }
            } else 0
        val end = if (activeIndex >= 0) start + lyricLines[activeIndex].text.length else start
        val styled = SpannableString(fullText)
        if (activeIndex >= 0 && end <= styled.length) {
            styled.setSpan(
                ForegroundColorSpan(android.graphics.Color.rgb(190, 130, 255)),
                start,
                end,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        view.text = styled
        if (activeIndex >= 0) {
            view.post {
                val layout = view.layout ?: return@post
                val line = layout.getLineForOffset(start)
                lyricsScroll?.smoothScrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
            }
        }
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        val repeatButton = requireBinding().playbackRepeat
        repeatButton.isChecked = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isChecked = isPlaying
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isChecked = isShuffled
    }

    private fun updatePager(queue: PagerQueue) {
        // Right now there's easily 140ms of frame skipping when going next/prev. This is primarily
        // the fault of specifically the nested bottom sheet UI setup, which is intractable to
        // optimize. If I don't do multiple remeasures/relayouts on every slightest state
        // instability
        // I will suddenly encounter insane issues where the sheet fails to measure, appears below
        // the sidebar, flies away, not changing with ui scale, etc, often only on third-party OEM
        // ROMs that randomly mangle  SDK APIs and the SystemUI chrome for no reason.
        //
        // Historically this was not an issue, as I did not animate next/prev. Now I do, and it's
        // highly noticeable. So at least for plain next/prev I have to hack around it, do not
        // execute any transition until the state has fully adjudicated and laid out the UI. It's
        // not effective for swiping but there's nothing I can do there.
        //
        // Eventually one day Claude Fable 6.7 will probably be able to figure out that you need to
        // reflect into System.FoobaCrumbo::beegieConnector(GoolaUtils.PlubBud) and call it
        // specifically with 0x189B31FA alongside disabling the AndroidX Helpo SuperCharge by
        // manually clobbering `BottomSheetM2InternalBoogieCompat::scrimbloManager` to null for it
        // to not actually randomly mangle the sheets and do it in 1 clean layout, but for now I
        // must do this to keep my sanity.
        //
        // Actual snippet here was codex, just cleaned & adapted it / cognitive ownership
        requireBinding().playbackPager.apply {
            if (!isAttachedToWindow) {
                post { updatePagerImpl(queue) }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
                // New version using post-Q frame hooks
                viewTreeObserver.registerFrameCommitCallback {
                    post { postOnAnimation { updatePagerImpl(queue) } }
                }
                postInvalidateOnAnimation()
            } else {
                // Let current layout happen, then wait for the next to conclude
                postOnAnimation { postOnAnimation { updatePagerImpl(queue) } }
            }
        }
    }

    private fun updatePagerImpl(queue: PagerQueue) {
        // Android insanity means this may be executed after view destruction
        // but only on some devices.
        val binding = binding ?: return

        val command = playbackModel.pagerCommand.consume()
        if (command == null) {
            // This probably shouldn't happen in practice, as QueueViewModel directly
            // attaches to PlaybackStateManager and will basically always initialize
            // with a command as a result.
            //
            // If it does happen we should just make sure the UI state is aligned. Don't
            // want broken UI.
            coverPagerAdapter.update(queue.queue, null)
            binding.playbackPager.setCurrentItem(queue.index, false)
            return
        }

        if (command.update != null) {
            // queue needs to be updated.
            coverPagerAdapter.update(queue.queue, command.update)
        }

        if (command.scroll != null) {
            // we need to scroll, however the smooth scroll only really looks best
            // when we are only doing next/prev due to various factors. better to
            // just not animate on outright gotos or queue updates
            val delta = binding.playbackPager.currentItem - command.scroll
            if (delta == 0) {
                // user scroll, carry on
                return
            }
            if (command.update == null && abs(delta) == 1) {
                binding.playbackPager.smoothScrollByPageTo(command.scroll)
            } else {
                binding.playbackPager.setCurrentItem(command.scroll, false)
            }
        }
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }
}
