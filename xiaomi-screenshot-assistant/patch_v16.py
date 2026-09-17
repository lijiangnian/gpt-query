from pathlib import Path

root = Path("xiaomi-screenshot-assistant-v1.1")
main = root / "app/src/main/java/com/vsme/screenshotassistant/MainActivity.kt"
engine = root / "app/src/main/java/com/vsme/screenshotassistant/AiTranslationEngine.kt"
gradle = root / "app/build.gradle.kts"

s = main.read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# 1) Camera return must survive Activity/process recreation.
# ---------------------------------------------------------------------------
if 'CAMERA_PREFS' not in s:
    s = s.replace(
        '        private const val REQUEST_CAMERA_THUMB = 2402\n',
        '        private const val REQUEST_CAMERA_THUMB = 2402\n'
        '        private const val CAMERA_PREFS = "camera_state"\n'
        '        private const val KEY_PENDING_CAMERA_URI = "pending_camera_uri"\n'
    )

old_oncreate = '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        handleIntent(intent)
    }
'''
new_oncreate = '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        pendingCameraUri = savedInstanceState?.getString(KEY_PENDING_CAMERA_URI)?.let(Uri::parse)
            ?: restorePendingCameraUri()
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCameraUri?.let { outState.putString(KEY_PENDING_CAMERA_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }
'''
if old_oncreate in s:
    s = s.replace(old_oncreate, new_oncreate)

# Restore persisted URI in onActivityResult and always clear persisted state.
s = s.replace(
    '''            REQUEST_CAMERA_FULL -> {
                val uri = pendingCameraUri
                pendingCameraUri = null
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    loadSharedImage(uri, MODE_TRANSLATE, deleteAfterLoad = true)
                } else {
                    if (uri != null) Thread { runCatching { contentResolver.delete(uri, null, null) } }.start()
                    if (resultCode != Activity.RESULT_CANCELED) showToast("拍照未完成")
                }
            }
''',
    '''            REQUEST_CAMERA_FULL -> {
                val uri = pendingCameraUri ?: restorePendingCameraUri()
                pendingCameraUri = null
                clearPendingCameraUri()
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    showIncomingLoading()
                    loadSharedImage(uri, MODE_TRANSLATE, deleteAfterLoad = true)
                } else {
                    if (uri != null) Thread { runCatching { contentResolver.delete(uri, null, null) } }.start()
                    if (resultCode != Activity.RESULT_CANCELED) showToast("拍照未完成")
                }
            }
'''
)

# Add persistence helpers and a Xiaomi/HyperOS onResume fallback. Some camera builds can
# restore the caller without delivering the old in-memory Activity result state reliably.
if 'private fun persistPendingCameraUri' not in s:
    marker = '''    @Suppress("DEPRECATION")
    private fun launchCamera() {
'''
    helpers = '''    private fun persistPendingCameraUri(uri: Uri) {
        getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_CAMERA_URI, uri.toString()).apply()
    }

    private fun restorePendingCameraUri(): Uri? {
        return getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_CAMERA_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun clearPendingCameraUri() {
        getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_CAMERA_URI).apply()
    }

    private fun cameraUriHasContent(uri: Uri): Boolean {
        return runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd -> fd.statSize > 1024L } ?: false
        }.getOrDefault(false)
    }

    private fun maybeRecoverCameraReturn() {
        val uri = pendingCameraUri ?: restorePendingCameraUri() ?: return
        Thread {
            try { Thread.sleep(220) } catch (_: InterruptedException) {}
            if (!cameraUriHasContent(uri)) return@Thread
            runOnUiThread {
                val latest = pendingCameraUri ?: restorePendingCameraUri()
                if (latest?.toString() != uri.toString()) return@runOnUiThread
                pendingCameraUri = null
                clearPendingCameraUri()
                showIncomingLoading()
                loadSharedImage(uri, MODE_TRANSLATE, deleteAfterLoad = true)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        maybeRecoverCameraReturn()
    }

'''
    if marker not in s:
        raise SystemExit("launchCamera marker not found")
    s = s.replace(marker, helpers + marker)

# Persist immediately before leaving the app for the system camera.
needle = '''            pendingCameraUri = uri
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri)
'''
replacement = '''            pendingCameraUri = uri
            persistPendingCameraUri(uri)
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri)
'''
if needle in s:
    s = s.replace(needle, replacement)

# Clear persisted state if launching camera itself fails.
s = s.replace(
    '''                .onFailure {
                    pendingCameraUri = null
                    Thread { runCatching { contentResolver.delete(uri, null, null) } }.start()
                    showToast("无法打开相机")
                }
''',
    '''                .onFailure {
                    pendingCameraUri = null
                    clearPendingCameraUri()
                    Thread { runCatching { contentResolver.delete(uri, null, null) } }.start()
                    showToast("无法打开相机")
                }
'''
)

# ---------------------------------------------------------------------------
# 2) Large camera photos: decode a bounded bitmap instead of decoding full sensor size.
# ---------------------------------------------------------------------------
old_decode = '''                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "无法读取截图" }
                    BitmapFactory.decodeStream(stream)
                }
'''
new_decode = '''                decodeBitmapForAssistant(uri)
'''
if old_decode in s:
    s = s.replace(old_decode, new_decode)

if 'private fun decodeBitmapForAssistant' not in s:
    marker = '''    private fun loadSharedImage(uri: Uri, autoMode: String?, deleteAfterLoad: Boolean = false) {
'''
    helper = '''    private fun decodeBitmapForAssistant(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) return null
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 3200) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) null else BitmapFactory.decodeStream(stream, null, options)
        }
    }

'''
    if marker not in s:
        raise SystemExit("loadSharedImage marker not found")
    s = s.replace(marker, helper + marker)

# Avoid duplicate loading indicator because v1.6 explicitly shows it on camera return.
s = s.replace(
    '''    private fun loadSharedImage(uri: Uri, autoMode: String?, deleteAfterLoad: Boolean = false) {
        showIncomingLoading()
''',
    '''    private fun loadSharedImage(uri: Uri, autoMode: String?, deleteAfterLoad: Boolean = false) {
        showIncomingLoading()
'''
)

# ---------------------------------------------------------------------------
# 3) Groq credential UX: editable local field + unmistakable test feedback.
# ---------------------------------------------------------------------------
s = s.replace(
    'setText(SecureStore.loadKey(this@MainActivity)); isEnabled=false; textSize = 15f;',
    'setText(SecureStore.loadKey(this@MainActivity)); isEnabled=true; textSize = 15f;'
)
s = s.replace(
    'setText(SecureStore.loadKey(this@MainActivity)); isEnabled = false; textSize = 15f;',
    'setText(SecureStore.loadKey(this@MainActivity)); isEnabled = true; textSize = 15f;'
)

# Make the test response visible both inline and as a Toast.
old_fold = '''                        status.text = result.fold(
                            { "连接成功 · ${provider.displayName}" },
                            { "连接失败：${it.message ?: "未知错误"}" }
                        )
'''
new_fold = '''                        val message = result.fold(
                            { "连接成功 · ${provider.displayName}" },
                            { "连接失败：${it.message ?: "未知错误"}" }
                        )
                        status.text = message
                        showToast(message)
'''
if old_fold in s:
    s = s.replace(old_fold, new_fold)

s = s.replace(
    'text = "截图或拍照图片不会上传，只发送本地 OCR 后的文字。Xiaomi 13 私人版使用内置 Groq 凭证。";',
    'text = "截图或拍照图片不会上传，只发送本地 OCR 后的文字。内置 Groq Key 若失效，可在下方直接粘贴新 Key 并测试。";'
)

main.write_text(s, encoding="utf-8")

# Replace the old translation-as-connectivity-test with a lightweight auth endpoint test.
e = engine.read_text(encoding="utf-8")
old_test = '''    fun testConnection(provider: AiProvider, apiKey: String, onResult: (Result<String>) -> Unit) {
        Thread {
            val result = runCatching {
                val fake = OcrBlock("b0", "Hello", android.graphics.Rect(0, 0, 100, 30), emptyList())
                execute(provider, apiKey, listOf(fake)).translations["b0"] ?: "连接成功"
            }
            onResult(result)
        }.start()
    }
'''
new_test = '''    fun testConnection(provider: AiProvider, apiKey: String, onResult: (Result<String>) -> Unit) {
        Thread {
            val result = runCatching {
                val cleanKey = apiKey.trim().replace("\\r", "").replace("\\n", "")
                require(cleanKey.isNotBlank()) { "AI 凭证不可用" }
                if (provider == AiProvider.GROQ) {
                    require(cleanKey.startsWith("gsk_") && cleanKey.none { it.isWhitespace() }) { "Groq 凭证格式异常" }
                }
                val testEndpoint = when (provider) {
                    AiProvider.GROQ -> "https://api.groq.com/openai/v1/models"
                    AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/models"
                }
                val connection = (URL(testEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $cleanKey")
                    setRequestProperty("Accept", "application/json")
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (status !in 200..299) {
                    val detail = body.replace("\\n", " ").take(240)
                    throw IllegalStateException("HTTP $status${if (detail.isBlank()) "" else " · $detail"}")
                }
                "连接成功"
            }
            onResult(result)
        }.start()
    }
'''
if old_test not in e:
    raise SystemExit("testConnection function marker not found")
e = e.replace(old_test, new_test)

# Ensure translation requests cannot appear to hang forever when networking is bad.
post_marker = '''        val connection = (URL(provider.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
'''
post_replacement = '''        val connection = (URL(provider.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 35000
'''
if post_marker in e and 'readTimeout = 35000' not in e:
    e = e.replace(post_marker, post_replacement)

engine.write_text(e, encoding="utf-8")

# Version bump.
g = gradle.read_text(encoding="utf-8")
g = g.replace("versionCode = 6", "versionCode = 7")
g = g.replace('versionName = "1.5.0-private"', 'versionName = "1.6.0-private"')
gradle.write_text(g, encoding="utf-8")

print("Applied Xiaomi Screenshot Assistant v1.6 camera lifecycle + key diagnostics patch")
