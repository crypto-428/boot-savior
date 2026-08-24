package com.yourapp.USBooter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yourapp.USBooter.service.FormatService
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.yourapp.USBooter.util.BootConfig
import com.yourapp.USBooter.util.IsoAnalyzer
import com.yourapp.USBooter.util.IsoSource
import com.yourapp.USBooter.util.ImageDirectory
import com.yourapp.USBooter.util.LayoutConfig
import com.yourapp.USBooter.util.UsbBulkStorageDevice
import com.yourapp.USBooter.util.WimReader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private lateinit var webInterface: WebAppInterface

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: ""
            val progress = intent?.getIntExtra("progress", 0) ?: 0
            val details = intent?.getStringExtra("details") ?: ""
            
            val ioJson = intent?.getStringExtra("io")
            val errorJson = intent?.getStringExtra("error")
            val diagnosticsJson = intent?.getStringExtra("diagnostics")
            val json = JSONObject().apply {
                put("status", status)
                put("progress", progress)
                put("details", details)
                if (!ioJson.isNullOrBlank()) {
                    runCatching { JSONObject(ioJson) }.getOrNull()?.let { put("io", it) }
                }
                if (!errorJson.isNullOrBlank()) {
                    put("error", runCatching { JSONObject(errorJson) }.getOrNull() ?: JSONObject.NULL)
                }
                if (!diagnosticsJson.isNullOrBlank()) {
                    runCatching { org.json.JSONArray(diagnosticsJson) }.getOrNull()
                        ?.let { put("diagnostics", it) }
                }
            }

            webView.evaluateJavascript("onFormatProgress($json)", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyWindowInsets()

        // Interrupt/resume journal: knowing how far the previous flash got is
        // what makes a safe resume (or rollback) possible after a crash.
        com.yourapp.USBooter.util.FlashJournal.attach(filesDir)

        webView = findViewById(R.id.webview)
        webInterface = WebAppInterface(this)

        setupWebView()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            progressReceiver, IntentFilter(FormatService.FORMAT_PROGRESS_ACTION)
        )

        requestNotificationPermissionIfNeeded()
    }

    /**
     * Android 15 (and Samsung's One UI before it) draws every app edge-to-edge:
     * without this the status bar and the gesture/navigation bar sit *on top* of
     * the WebView, which is why the top row and the bottom buttons were hidden
     * on the S24 Ultra. The content is padded by the system bar insets instead
     * of changing any UI element, and the bar icons are forced to a contrast
     * that stays visible over the app background.
     */
    private fun applyWindowInsets() {
        val root = findViewById<android.view.View>(android.R.id.content)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        // Pre-Android-15 devices still honour these; a translucent scrim keeps
        // the icons readable whatever the page paints underneath.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = if (night) 0xFF101418.toInt() else 0xFFF5F7FA.toInt()
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (night) 0xFF101418.toInt() else 0xFFF5F7FA.toInt()
        }
    }

    /** Writes the last flash report to a file and offers it through the share sheet. */
    fun exportFlashReport() {
        runOnUiThread {
            try {
                val dir = File(cacheDir, "reports").apply { mkdirs() }
                val file = File(dir, "usbooter-report.txt")
                file.writeText(com.yourapp.USBooter.util.FlashReport.render())
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "USBooter flash report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Export verification report"))
            } catch (e: Exception) {
                showToast("Could not export the report: ${e.message}")
            }
        }
    }

    /**
     * Lets the user choose *where* the detailed log is saved (Downloads, Drive,
     * anywhere the system file picker offers), so it can be attached to a bug
     * report later instead of only being shared straight away.
     */
    private val createLogDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(com.yourapp.USBooter.util.FlashReport.render().toByteArray())
                } ?: throw java.io.IOException("The chosen location could not be opened")
                showToast("Log saved")
            } catch (e: Exception) {
                showToast("Could not save the log: ${e.message}")
            }
        }

    /** Opens the system "save as" dialog for the detailed conversion/build log. */
    fun saveFlashLogToFile() {
        runOnUiThread {
            if (!com.yourapp.USBooter.util.FlashReport.hasContent()) {
                showToast("There is no log to save yet")
                return@runOnUiThread
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            runCatching { createLogDocument.launch("usbooter-log-$stamp.txt") }
                .onFailure { showToast("No app can save files on this device") }
        }
    }



    /** Android 13+ needs an explicit grant before the progress notification shows. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // These settings ensure the UI adapts correctly to different screen 
            // densities and system font scales without breaking the layout.
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        
        webView.addJavascriptInterface(webInterface, "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webInterface.rescanDrives()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e("USBooterUI", "${message.message()} @${message.lineNumber()}")
                }
                return true
            }
        }
        
        webView.loadUrl("file:///android_asset/index.html")
    }

    fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** SAF picker for the ISO; the result is analysed off the UI thread and handed back to the WebView. */
    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            webView.evaluateJavascript("onIsoCancelled()", null)
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        thread {
            try {
                IsoSource.open(this, uri).use { source ->
                    val info = IsoAnalyzer.analyze(source)
                    val directory = runCatching { ImageDirectory.read(source) }.getOrNull()
                    val editions = runCatching {
                        val install = directory?.files?.firstOrNull {
                            it.path.trim('/').equals("sources/install.wim", ignoreCase = true)
                        }
                        if (install == null) emptyList() else WimReader.read(source, install)?.images.orEmpty()
                    }.getOrElse { emptyList() }
                    // Exact persistence behaviour, generated by the same code that
                    // writes the GRUB menu and the ext2 volume, so the UI can show
                    // it before anything is flashed.
                    val persistence = if (info.supportsPersistence)
                        com.yourapp.USBooter.util.GrubConfigBuilder.persistencePreview(
                            directory?.entries.orEmpty(), info.volumeLabel, info.persistenceFamily
                        ) else "" to ""
                    val suggested = info.resolveMode(com.yourapp.USBooter.util.BootMode.AUTO)
                    val json = JSONObject().apply {
                        put("uri", uri.toString())
                        put("name", info.displayName)
                        put("sizeBytes", info.sizeBytes)
                        put("volumeLabel", info.volumeLabel)
                        put("isHybrid", info.isHybrid)
                        put("hasEfiBoot", info.hasEfiBoot || info.hasEfiBootFile)
                        put("hasBiosBoot", info.hasBiosBoot)
                        put("isPuppyLinux", info.isPuppyLinux)
                        put("isWindows", info.isWindows)
                        put("canBios", info.canBiosBootUniversal)
                        put("largestFileBytes", info.largestFileBytes)
                        put("directoryFormat", info.directoryFormat)
                        put("fileCount", info.fileCount)
                        put("installImagePath", info.installImagePath)
                        put("installImageBytes", info.installImageBytes)
                        put("needsSplitLayout", info.needsSplitLayout)
                        put("supportsPersistence", info.supportsPersistence)
                        put("persistenceLabel", info.persistenceLabel)
                        put("persistenceFamily", info.persistenceFamily)
                        put("persistenceArgs", persistence.first)
                        put("persistenceConf", persistence.second)
                        put("windowsSummary", info.windowsLayoutSummary)
                        put("isWindowsPe", info.isWindowsPe)
                        put("profileId", info.profileId)
                        put("profileName", info.profileName)
                        put("profileCategory", info.profileCategory)
                        put("profileAdvice", info.profileAdvice)
                        put("profileMode", info.profileMode.name)
                        put("editions", JSONArray().apply {
                            editions.forEach { image ->
                                put(JSONObject().apply {
                                    put("index", image.index)
                                    put("label", image.label)
                                    put("editionId", image.editionId)
                                    put("architecture", image.architecture)
                                })
                            }
                        })
                        put("suggestedMode", suggested.name)
                        // What the image can actually boot on, so the UI can pre-select
                        // the firmware target and the matching MBR/GPT preparation.
                        put(
                            "suggestedFirmware",
                            when {
                                info.canBiosBootUniversal && (info.hasEfiBoot || info.hasEfiBootFile) -> "BOTH"
                                info.isHybrid && info.hasBiosBoot && (info.hasEfiBoot || info.hasEfiBootFile) -> "BOTH"
                                info.hasEfiBoot || info.hasEfiBootFile -> "UEFI"
                                else -> "BIOS"
                            }
                        )
                        put("warnings", org.json.JSONArray(info.warningsFor(suggested)))
                    }
                    runOnUiThread { webView.evaluateJavascript("onIsoSelected($json)", null) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast("Could not read that ISO: ${e.message}")
                    webView.evaluateJavascript("onIsoCancelled()", null)
                }
            }
        }
    }

    fun pickIso() {
        runOnUiThread {
            isoPicker.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*"))
        }
    }

    fun startFormatService(
        deviceName: String,
        model: String,
        config: LayoutConfig,
        bootConfig: BootConfig? = null,
        dryRun: Boolean = false
    ) {
        val intent = Intent(this, FormatService::class.java).apply {
            putExtra("drive_device_name", deviceName)
            putExtra("drive_model", model)
            putExtra("config", config)
            bootConfig?.let { putExtra("boot_config", it) }
            putExtra("dry_run", dryRun)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun safelyRemoveDrive(deviceName: String) {
        val device = webInterface.detector.findDeviceByName(deviceName)
        if (device == null) {
            showToast("Device not found")
            return
        }

        thread {
            val storage = UsbBulkStorageDevice.open(getSystemService(Context.USB_SERVICE) as UsbManager, device)
            if (storage != null) {
                try {
                    storage.stopUnit()
                    runOnUiThread {
                        showToast("Device can be safely removed")
                        webInterface.rescanDrives()
                    }
                } catch (e: Exception) {
                    runOnUiThread { showToast("Failed to safely remove: ${e.message}") }
                } finally {
                    storage.close()
                }
            } else {
                runOnUiThread { showToast("Could not open device connection") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
