package org.bitfennec.lime.ui.fragment.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeFilesManager
import org.bitfennec.lime.data.theme.ThemePreset
import org.bitfennec.lime.keyboard.KeyboardPreviewView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.DarkenColorFilter
import org.bitfennec.lime.utils.parcelable
import org.bitfennec.lime.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.bitfennec.lime.utils.dp
import java.io.File

class CustomThemeActivity : AppCompatActivity() {

    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Theme.Custom?, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Theme.Custom?): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private lateinit var toolbar: Toolbar
    private lateinit var previewUi: KeyboardPreviewView
    private lateinit var rowRecrop: LinearLayout
    private lateinit var rowDarkKeys: LinearLayout
    private lateinit var switchDarkKeys: SwitchCompat
    private lateinit var tvDarknessValue: TextView
    private lateinit var seekbarDarkness: SeekBar
    private lateinit var btnSaveApply: TextView
    private lateinit var btnDeleteTheme: TextView

    private var newCreated = true
    private lateinit var theme: Theme.Custom

    private class BackgroundStates {
        lateinit var launcher: ActivityResultLauncher<CropImageContractOptions>
        var srcImageExtension: String? = null
        var srcImageBuffer: ByteArray? = null
        var cropRect: Rect? = null
        lateinit var croppedBitmap: Bitmap
        lateinit var filteredDrawable: BitmapDrawable
        lateinit var srcImageFile: File
        lateinit var croppedImageFile: File
    }

    private val backgroundStates by lazy { BackgroundStates() }

    private inline fun whenHasBackground(
        block: BackgroundStates.(Theme.Custom.CustomBackground) -> Unit,
    ) {
        if (theme.backgroundImage != null) {
            block(backgroundStates, theme.backgroundImage!!)
        }
    }

    private fun BackgroundStates.setKeyVariant(background: Theme.Custom.CustomBackground, darkKeys: Boolean) {
        theme = if (darkKeys)
            ThemePreset.TransparentLight.deriveCustomBackground(
                theme.name,
                background.croppedFilePath,
                background.srcFilePath,
                seekbarDarkness.progress,
                background.cropRect
            ) else
            ThemePreset.TransparentDark.deriveCustomBackground(
                theme.name,
                background.croppedFilePath,
                background.srcFilePath,
                seekbarDarkness.progress,
                background.cropRect
            )
        previewUi.setTheme(theme, filteredDrawable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImeEnvironment.initData(this)
        setContentView(R.layout.activity_custom_theme)

        toolbar = findViewById(R.id.toolbar)
        previewUi = findViewById(R.id.keyboard_preview_view)
        rowRecrop = findViewById(R.id.row_recrop)
        rowDarkKeys = findViewById(R.id.row_dark_keys)
        switchDarkKeys = findViewById(R.id.switch_dark_keys)
        tvDarknessValue = findViewById(R.id.tv_darkness_value)
        seekbarDarkness = findViewById(R.id.seekbar_darkness)
        btnSaveApply = findViewById(R.id.btn_save_apply)
        btnDeleteTheme = findViewById(R.id.btn_delete_theme)

        // Restore existing theme or initialize new theme
        val originTheme = intent?.parcelable<Theme.Custom>(ORIGIN_THEME)?.also { t ->
            theme = t
            whenHasBackground {
                croppedImageFile = File(it.croppedFilePath)
                srcImageFile = File(it.srcFilePath)
                cropRect = it.cropRect
                croppedBitmap = BitmapFactory.decodeFile(it.croppedFilePath)
                filteredDrawable = croppedBitmap.toDrawable(resources)
            }
            newCreated = false
        }

        if (originTheme == null) {
            val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
            backgroundStates.apply {
                croppedImageFile = c
                srcImageFile = s
            }
            theme = ThemePreset.TransparentDark.deriveCustomBackground(n, c.path, s.path)
        }

        // Toolbar configuration
        toolbar.title = getString(if (newCreated) R.string.custom_theme_title_create else R.string.custom_theme_title_edit)
        toolbar.setNavigationOnClickListener {
            cancel()
        }

        // Bind bottom action buttons and cards
        btnSaveApply.setOnClickListener {
            done()
        }

        if (!newCreated) {
            btnDeleteTheme.visibility = View.VISIBLE
            btnDeleteTheme.setOnClickListener {
                promptDelete()
            }
        } else {
            btnDeleteTheme.visibility = View.GONE
        }

        whenHasBackground { background ->
            seekbarDarkness.progress = background.brightness
            switchDarkKeys.isChecked = !theme.isDark

            launcher = registerForActivityResult(CropImageContract()) { result ->
                if (!result.isSuccessful) {
                    if (newCreated) cancel() else return@registerForActivityResult
                } else {
                    val origUri = result.originalUri
                    if (newCreated && origUri != null) {
                        srcImageExtension = runCatching {
                            contentResolver.getType(origUri)?.let { mime ->
                                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                            }
                        }.getOrNull() ?: "png"
                        srcImageBuffer = runCatching {
                            contentResolver.openInputStream(origUri)?.use { x -> x.readBytes() }
                        }.getOrNull()
                    }
                    cropRect = result.cropRect

                    val bitmap = result.getBitmap(this@CustomThemeActivity)
                    if (bitmap != null) {
                        val targetW = if (ImeEnvironment.skbWidth > 0) ImeEnvironment.skbWidth else resources.displayMetrics.widthPixels
                        val targetH = if (ImeEnvironment.inputAreaHeight > 0) ImeEnvironment.inputAreaHeight else (targetW * 0.65f).toInt()
                        croppedBitmap = bitmap.scale(
                            targetW,
                            targetH,
                            true
                        )
                        filteredDrawable = croppedBitmap.toDrawable(resources)
                        updateState()
                    } else {
                        if (newCreated) cancel()
                    }
                }
            }

            rowRecrop.setOnClickListener {
                launchCrop(ImeEnvironment.skbWidth, ImeEnvironment.inputAreaHeight)
            }

            rowDarkKeys.setOnClickListener {
                switchDarkKeys.toggle()
            }

            switchDarkKeys.setOnCheckedChangeListener { _, isChecked ->
                setKeyVariant(background, darkKeys = isChecked)
            }

            seekbarDarkness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateState()
                }
            })
        }

        if (newCreated) {
            whenHasBackground {
                launchCrop(ImeEnvironment.skbWidth, ImeEnvironment.inputAreaHeight)
            }
        } else {
            whenHasBackground {
                updateState()
            }
        }

        onBackPressedDispatcher.addCallback {
            cancel()
        }
    }

    private fun BackgroundStates.launchCrop(w: Int, h: Int) {
        val safeW = if (w > 0) w else resources.displayMetrics.widthPixels
        val safeH = if (h > 0) h else (safeW * 0.65f).toInt()
        launcher.launch(
            CropImageContractOptions(
                uri = srcImageFile.takeIf { it.exists() }?.toUri(),
                CropImageOptions(
                    activityTitle = getString(R.string.recrop_wallpaper_title),
                    toolbarColor = "#1E1F22".toColorInt(),
                    toolbarTitleColor = Color.WHITE,
                    toolbarBackButtonColor = Color.WHITE,
                    activityMenuIconColor = Color.WHITE,
                    activityMenuTextColor = "#0A84FF".toColorInt(),
                    cropMenuCropButtonTitle = getString(R.string.done),
                    cropMenuCropButtonIcon = R.drawable.ic_menu_done,
                    activityBackgroundColor = "#121316".toColorInt(),
                    backgroundColor = "#B3000000".toColorInt(),
                    borderLineColor = "#80FFFFFF".toColorInt(),
                    borderLineThickness = dp(1.5f).toFloat(),
                    borderCornerColor = "#0A84FF".toColorInt(),
                    borderCornerThickness = dp(3.5f).toFloat(),
                    borderCornerLength = dp(18f).toFloat(),
                    borderCornerOffset = 0f,
                    cornerShape = CropImageView.CropCornerShape.OVAL,
                    cropCornerRadius = dp(8f).toFloat(),
                    guidelines = CropImageView.Guidelines.ON_TOUCH,
                    guidelinesColor = "#40FFFFFF".toColorInt(),
                    guidelinesThickness = dp(1f).toFloat(),
                    aspectRatioX = safeW,
                    aspectRatioY = safeH,
                    fixAspectRatio = true,
                    initialCropWindowRectangle = cropRect,
                    imageSourceIncludeGallery = true,
                    imageSourceIncludeCamera = false,
                    showProgressBar = true,
                    progressBarColor = "#0A84FF".toColorInt(),
                    outputCompressFormat = Bitmap.CompressFormat.PNG
                )
            )
        )
    }

    @SuppressLint("SetTextI18n")
    private fun BackgroundStates.updateState() {
        val progress = seekbarDarkness.progress
        tvDarknessValue.text = "$progress%"
        filteredDrawable.colorFilter = DarkenColorFilter(100 - progress)
        previewUi.setTheme(theme, filteredDrawable)
    }

    private fun cancel() {
        setResult(
            Activity.RESULT_CANCELED,
            Intent().apply { putExtra(RESULT, null as BackgroundResult?) }
        )
        finish()
    }

    private fun done() {
        lifecycleScope.withLoadingDialog(this) {
            whenHasBackground {
                withContext(Dispatchers.IO) {
                    croppedImageFile.delete()
                    croppedImageFile.outputStream().use {
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (newCreated) {
                        if (srcImageExtension != null) {
                            srcImageFile = File("${srcImageFile.absolutePath}.$srcImageExtension")
                            theme = theme.copy(
                                backgroundImage = it.copy(
                                    srcFilePath = srcImageFile.absolutePath
                                )
                            )
                        }
                        srcImageBuffer?.let { buf ->
                            srcImageFile.writeBytes(buf)
                        }
                    }
                }
            }
            setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    var newTheme = theme
                    whenHasBackground {
                        newTheme = theme.copy(
                            backgroundImage = it.copy(
                                brightness = seekbarDarkness.progress,
                                cropRect = cropRect
                            )
                        )
                    }
                    putExtra(
                        RESULT,
                        if (newCreated)
                            BackgroundResult.Created(newTheme)
                        else
                            BackgroundResult.Updated(newTheme)
                    )
                })
            finish()
        }
    }

    private fun delete() {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(RESULT, BackgroundResult.Deleted(theme.name))
            }
        )
        finish()
    }

    private fun promptDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, theme.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delete()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"
    }
}
