from pathlib import Path

root = Path("xiaomi-screenshot-assistant-v1.1")
main = root / "app/src/main/java/com/vsme/screenshotassistant/MainActivity.kt"
gradle = root / "app/build.gradle.kts"

s = main.read_text(encoding="utf-8")

# Imports needed for full-resolution camera capture through MediaStore.
if "import android.content.ContentValues" not in s:
    s = s.replace("import android.content.ClipboardManager\n", "import android.content.ClipboardManager\nimport android.content.ContentValues\n")
if "import android.provider.MediaStore" not in s:
    s = s.replace("import android.os.Bundle\n", "import android.os.Bundle\nimport android.provider.MediaStore\n")

# Camera request codes.
if "REQUEST_CAMERA_FULL" not in s:
    s = s.replace(
        '        const val MODE_TRANSLATE = "translate"\n',
        '        const val MODE_TRANSLATE = "translate"\n'
        '        private const val REQUEST_CAMERA_FULL = 2401\n'
        '        private const val REQUEST_CAMERA_THUMB = 2402\n'
    )

# Keep track of the temporary MediaStore URI used by the system camera.
if "pendingCameraUri" not in s:
    s = s.replace(
        "    private var extractionMode = false\n",
        "    private var extractionMode = false\n    private var pendingCameraUri: Uri? = null\n"
    )

# Receive the result from the system camera. Android 10+ uses a temporary full-resolution
# MediaStore row; older Android versions fall back to the camera thumbnail result.
if "override fun onActivityResult" not in s:
    marker = '''    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
'''
    addition = marker + '''
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CAMERA_FULL -> {
                val uri = pendingCameraUri
                pendingCameraUri = null
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    loadSharedImage(uri, MODE_TRANSLATE, deleteAfterLoad = true)
                } else {
                    if (uri != null) Thread { runCatching { contentResolver.delete(uri, null, null) } }.start()
                    if (resultCode != Activity.RESULT_CANCELED) showToast("拍照未完成")
                }
            }
            REQUEST_CAMERA_THUMB -> {
                if (resultCode == Activity.RESULT_OK) {
                    @Suppress("DEPRECATION")
                    val bitmap = data?.extras?.get("data") as? Bitmap
                    if (bitmap == null) {
                        showToast("无法读取拍照结果")
                    } else {
                        currentBitmap = bitmap
                        currentOcr = null
                        currentTranslation = null
                        extractionMode = false
                        buildScreenshotScreen(bitmap)
                        translateWholeScreenshot()
                    }
                }
            }
        }
    }
'''
    if marker not in s:
        raise SystemExit("onNewIntent marker not found")
    s = s.replace(marker, addition)

# Allow camera captures to reuse the same image-loading path and delete the temporary
# MediaStore item once the bitmap has been decoded.
s = s.replace(
    "    private fun loadSharedImage(uri: Uri, autoMode: String?) {\n",
    "    private fun loadSharedImage(uri: Uri, autoMode: String?, deleteAfterLoad: Boolean = false) {\n"
)
if "if (deleteAfterLoad) runCatching" not in s:
    s = s.replace(
        "            }.getOrNull()\n            runOnUiThread {\n",
        "            }.getOrNull()\n            if (deleteAfterLoad) runCatching { contentResolver.delete(uri, null, null) }\n            runOnUiThread {\n"
    )

# Launch the installed camera app. On modern Android use a full-resolution MediaStore URI
# without asking for storage or camera permission; on older Android keep a compatible fallback.
if "private fun launchCamera()" not in s:
    marker = "    private fun showHome() {\n"
    camera_fn = '''    @Suppress("DEPRECATION")
    private fun launchCamera() {
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (capture.resolveActivity(packageManager) == null) {
            showToast("没有找到可用的相机应用")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ScreenshotAssistant_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            if (uri == null) {
                showToast("无法创建拍照缓存")
                return
            }
            pendingCameraUri = uri
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivityForResult(capture, REQUEST_CAMERA_FULL) }
                .onFailure {
                    pendingCameraUri = null
                    Thread { runCatching { contentResolver.delete(uri, null, null) } }.start()
                    showToast("无法打开相机")
                }
        } else {
            runCatching { startActivityForResult(capture, REQUEST_CAMERA_THUMB) }
                .onFailure { showToast("无法打开相机") }
        }
    }

'''
    if marker not in s:
        raise SystemExit("showHome marker not found")
    s = s.replace(marker, camera_fn + marker)

# Add a visible home-card entry. It opens the system camera and immediately translates
# the captured image after local OCR.
camera_card = '''        column.addView(infoCard("拍照翻译", "直接调用手机摄像机拍照，拍完自动识别并进入 AI 原位翻译。", "拍") {
            launchCamera()
        }, marginTop(14))
'''
if 'infoCard("拍照翻译"' not in s:
    anchor = '''        column.addView(infoCard("AI 原位翻译", "${provider.displayName} · ${SecureStore.maskedKey(this)}\\nAI 完整翻译后直接覆盖原文字位置。", "译") {
            showAiSettings()
        })
'''
    if anchor not in s:
        raise SystemExit("AI card marker not found")
    s = s.replace(anchor, anchor + camera_card)

# Generalize a few labels so the same screen reads naturally for screenshots and photos.
s = s.replace("要对这张截图做什么？", "要对这张图片做什么？")
s = s.replace("正在识别截图文字…", "正在识别图片文字…")
s = s.replace("截图图片不会上传，只发送本地 OCR 后的文字。", "截图或拍照图片不会上传，只发送本地 OCR 后的文字。")
s = s.replace("截图分享后可直接 AI 翻译，无需再填写 Key。", "截图分享或拍照后可直接 AI 翻译，无需再填写 Key。")

main.write_text(s, encoding="utf-8")

# Bump app version for the camera-enabled build.
g = gradle.read_text(encoding="utf-8")
g = g.replace("versionCode = 5", "versionCode = 6")
g = g.replace('versionName = "1.4.0-private"', 'versionName = "1.5.0-private"')
gradle.write_text(g, encoding="utf-8")

print("Applied Xiaomi Screenshot Assistant v1.5 camera translation patch")
