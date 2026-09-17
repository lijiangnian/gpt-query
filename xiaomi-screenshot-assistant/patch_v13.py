from pathlib import Path
import re

root = Path('xiaomi-screenshot-assistant-v1.1')

# Only ONE system share entry: MainActivity. Remove AI direct-share target.
p = root / 'app/src/main/AndroidManifest.xml'
s = p.read_text()
s = re.sub(r'\n\s*<meta-data android:name="android\.app\.shortcuts" android:resource="@xml/shortcuts"\s*/>', '', s)
s = re.sub(r'\n\s*<activity\s+android:name="\.AiTranslateShareActivity".*?</activity>', '', s, flags=re.S)
p.write_text(s)

# Xiaomi 13 private UI + native Android text-selection handles.
p = root / 'app/src/main/java/com/vsme/screenshotassistant/MainActivity.kt'
s = p.read_text()
s = s.replace('        ShareShortcutPublisher.publish(this)\n', '')
s = s.replace('textSize = 15f; singleLine = true; inputType =', 'textSize = 15f; setSingleLine(true); inputType =')
s = s.replace('三指截图 → 发送 → AI翻译截图 / 截图助手。\\n已发布系统分享快捷目标，HyperOS 支持时会出现在顶部快捷区。', '三指截图 → 发送 → 截图助手。\\n分享面板只保留一个入口，进入后再选 AI 翻译或提取文字。')
s = s.replace('单击磁吸整行；双击/长按选词；滑动连续多选。命中范围专门放大。', '长按文字出现系统双选择柄，可自由跨行拖动；支持系统放大镜、复制和全选。')
s = s.replace('建议先点上面的 AI 原位翻译设置免费 API Key。\\n之后截图分享即可使用。', 'Xiaomi 13 私人版已内置 Groq 凭证。\\n截图分享后可直接 AI 翻译或提取文字。')
s = s.replace('"${provider.displayName} · ${SecureStore.maskedKey(this)}\\nAI 完整翻译后直接覆盖原文字位置。"', '"Groq 私人版 · ${SecureStore.maskedKey(this)}\\nAI 完整翻译后直接覆盖原文字位置。"')
s = s.replace('row.addView(actionCard("文", "提取文字", "手指磁吸点选") { enterExtractionMode() }', 'row.addView(actionCard("文", "提取文字", "长按拖两端自由选择") { enterExtractionMode() }')
s = s.replace('row.addView(bottomButton("智能取字", false) { enterExtractionMode() }', 'row.addView(bottomButton("提取文字", false) { enterExtractionMode() }')
s = s.replace('routerBtn = TextView(this).apply { text="OpenRouter 免费"; textSize=15f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setOnClickListener { provider=AiProvider.OPENROUTER; refreshProviderButtons() } }', 'routerBtn = TextView(this).apply { text="OpenRouter（关闭）"; textSize=15f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; isEnabled=false; alpha=.45f }')
s = s.replace('setText(SecureStore.loadKey(this@MainActivity)); textSize = 15f;', 'setText(SecureStore.loadKey(this@MainActivity)); isEnabled=false; textSize = 15f;')
s = s.replace('截图图片不会上传，只发送本地 OCR 后的文字。API Key 使用 Android Keystore 加密保存在本机。', '截图图片不会上传，只发送本地 OCR 后的文字。Xiaomi 13 私人版使用内置 Groq 凭证。')

replacement = r'''    private fun enterExtractionMode() {
        showLoading("正在识别文字…")
        ensureOcr { ocr ->
            hideLoading(); extractionMode = true
            smartView?.setDisplayMode(ImageDisplayMode.ORIGINAL)
            smartView?.setOcrResult(ocr)
            smartView?.setSelectionEnabled(false)
            showSelectableOcrSheet(ocr)
        }
    }

    private fun showSelectableOcrSheet(ocr: OcrResult) {
        val fullText = ocr.lines.joinToString(System.lineSeparator()) { it.text }.trim()
        if (fullText.isBlank()) { showToast("没有识别到可提取文字"); return }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
            background = topRoundRect(Color.WHITE, 32f)
        }
        shell.addView(handleView())

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "提取文字"; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(25,25,27))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        var currentTextSize = 20f
        lateinit var selectable: TextView
        fun sizeButton(label: String, delta: Float) = TextView(this).apply {
            text = label; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(49,87,174)); background = roundRect(Color.rgb(239,244,255), 16f)
            setOnClickListener {
                currentTextSize = (currentTextSize + delta).coerceIn(16f, 30f)
                selectable.textSize = currentTextSize
            }
        }
        titleRow.addView(sizeButton("A−", -2f), LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginEnd = dp(6) })
        titleRow.addView(sizeButton("A+", 2f), LinearLayout.LayoutParams(dp(48), dp(42)))
        shell.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(6) })

        shell.addView(TextView(this).apply {
            text = "长按文字 → 出现左右两个选择柄 → 任意拖动、跨行选择。拖动时由系统放大镜辅助定位。"
            textSize = 13f; setTextColor(Color.rgb(112,112,118)); setLineSpacing(dpF(3f),1f); setPadding(0,dp(8),0,dp(12))
        })

        selectable = TextView(this).apply {
            text = fullText
            textSize = currentTextSize
            setTextColor(Color.rgb(30,30,33))
            setLineSpacing(dpF(7f), 1.08f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundRect(Color.rgb(247,247,249), 22f)
            setTextIsSelectable(true)
            customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                private val aiItemId = 0x5A11
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    menu.add(0, aiItemId, 0, "AI翻译").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    return true
                }
                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean = false
                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                    if (item.itemId != aiItemId) return false
                    val start = selectionStart.coerceAtLeast(0)
                    val end = selectionEnd.coerceAtLeast(0)
                    if (start == end) { showToast("请先拖动两端选择文字"); return true }
                    val selected = text.substring(minOf(start,end), maxOf(start,end)).toString()
                    mode.finish()
                    translateRawText(selected)
                    return true
                }
                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
        }
        val scroll = ScrollView(this).apply {
            addView(selectable, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        row.addView(bottomButton("复制全部", true) { copyToClipboard(fullText) }, LinearLayout.LayoutParams(0,dp(58),1f).apply { marginEnd=dp(5) })
        row.addView(bottomButton("AI翻译全部", false) { translateRawText(fullText) }, LinearLayout.LayoutParams(0,dp(58),1f).apply { marginStart=dp(5); marginEnd=dp(5) })
        row.addView(bottomButton("完成", false) { dialog.dismiss() }, LinearLayout.LayoutParams(0,dp(58),1f).apply { marginStart=dp(5) })
        shell.addView(row)

        dialog.setContentView(shell)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount=.18f }
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels*.78f).toInt())
        }
    }

    private fun translateRawText(text: String) {
        if (text.isBlank()) { showToast("没有可翻译的文字"); return }
        val key = SecureStore.loadKey(this)
        showLoading("AI 正在翻译选中文字…")
        val fake = OcrBlock("b0", text, android.graphics.Rect(0,0,100,30), emptyList())
        AiTranslationEngine.translateBlocks(SecureStore.loadProvider(this), key, listOf(fake),
            onSuccess = { result -> runOnUiThread { hideLoading(); showTextSheet("AI 翻译", result.fullText, "复制译文") { copyToClipboard(result.fullText) } } },
            onError = { error -> runOnUiThread { hideLoading(); showTranslationFailure(error) } }
        )
    }

    private fun translateSelected() {'''

pattern = r'    private fun enterExtractionMode\(\) \{.*?    private fun translateSelected\(\) \{'
s, n = re.subn(pattern, lambda _m: replacement, s, flags=re.S)
if n != 1:
    raise RuntimeError(f'extraction replacement count={n}')

old_tail = r'''        val text = smartView?.selectedText().orEmpty()
        if (text.isBlank()) { showToast("先轻点或滑动选择文字"); return }
        val key = SecureStore.loadKey(this)
        if (key.isBlank()) { showAiSettings(afterSave = { translateSelected() }); return }
        showLoading("AI 正在翻译选中文字…")
        val fake = OcrBlock("b0", text, android.graphics.Rect(0,0,100,30), emptyList())
        AiTranslationEngine.translateBlocks(SecureStore.loadProvider(this), key, listOf(fake),
            onSuccess = { result -> runOnUiThread { hideLoading(); showTextSheet("AI 翻译", result.fullText, "复制译文") { copyToClipboard(result.fullText) } } },
            onError = { error -> runOnUiThread { hideLoading(); showTranslationFailure(error) } }
        )
    }'''
new_tail = r'''        val text = smartView?.selectedText().orEmpty()
        if (text.isBlank()) { showToast("请先选择文字"); return }
        translateRawText(text)
    }'''
s = s.replace(old_tail, new_tail)
p.write_text(s)

# Private non-secret placeholder; final APK is patched locally, never in the public repo.
p = root / 'app/src/main/java/com/vsme/screenshotassistant/SecureStore.kt'
p.write_text('''package com.vsme.screenshotassistant\n\nimport android.content.Context\n\nobject SecureStore {\n    private const val BUILT_IN_KEY = "gsk_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"\n    fun save(context: Context, provider: AiProvider, apiKey: String) {}\n    fun loadProvider(context: Context): AiProvider = AiProvider.GROQ\n    fun loadKey(context: Context): String = BUILT_IN_KEY\n    fun maskedKey(context: Context): String = "私人内置"\n}\n''')

p = root / 'app/src/main/java/com/vsme/screenshotassistant/AiTranslationEngine.kt'
s = p.read_text()
s = s.replace('require(apiKey.isNotBlank()) { "请先填写 AI API Key" }', 'require(apiKey.isNotBlank()) { "AI 凭证不可用" }\n        val cleanKey = apiKey.trim().replace("\\r", "").replace("\\n", "")\n        require(cleanKey.startsWith("gsk_") && cleanKey.none { it.isWhitespace() }) { "Groq 凭证格式异常" }')
s = s.replace('setRequestProperty("Authorization", "Bearer $apiKey")', 'setRequestProperty("Authorization", "Bearer $cleanKey")')
p.write_text(s)

p = root / 'app/build.gradle.kts'
s = p.read_text().replace('versionCode = 2', 'versionCode = 4').replace('versionName = "1.1.0"', 'versionName = "1.3.0-private"')
p.write_text(s)
