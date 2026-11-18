package ca.activitycalendar

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import java.net.URLEncoder
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.reflect.KClass

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var client: HealthConnectClient? = null

    private val types: List<KClass<out Record>> = listOf(
        StepsRecord::class,
        DistanceRecord::class,
        TotalCaloriesBurnedRecord::class,
        FloorsClimbedRecord::class,
        RestingHeartRateRecord::class,
        HeartRateRecord::class,
        SleepSessionRecord::class,
        ExerciseSessionRecord::class,
        WeightRecord::class,
    )
    private val permissions: Set<String> = types.map { HealthPermission.getReadPermission(it) }.toSet()

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            js("window.onHealthPermission&&onHealthPermission(${granted.size})")
        }

    // Lets the page's "choose files" button open the phone's file picker (used by Import).
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            val data = res.data
            val uris: Array<Uri>? = if (res.resultCode != RESULT_OK || data == null) null else {
                val clip = data.clipData
                if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
                else data.data?.let { arrayOf(it) }
            }
            cb?.onReceiveValue(uris)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            client = HealthConnectClient.getOrCreate(this)
        }

        // Serve the bundled page from a real https origin so its saved data (localStorage) persists.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#1F2328"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    pickFiles.launch(intent)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)

        // Keep the page clear of the status bar and navigation bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        // Back closes an open panel or edit form first, then leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.appBack?appBack():false") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) js("window.onAppResume&&onAppResume()")
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    inner class Bridge {
        @JavascriptInterface
        fun status(): String = when (HealthConnectClient.getSdkStatus(this@MainActivity)) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "unavailable"
        }

        @JavascriptInterface
        fun connect() = runOnUiThread { requestPermissions.launch(permissions) }

        @JavascriptInterface
        fun openSettings() = runOnUiThread {
            try { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun version(): Int = currentVersion()

        // Food search through Open Food Facts (free, open food database).
        @JavascriptInterface
        fun foodSearch(query: String) {
            lifecycleScope.launch {
                val out = try { withContext(Dispatchers.IO) { searchFood(query.take(80)) } } catch (e: Exception) { error(e.message ?: "no connection") }
                js("window.onFoodResults&&onFoodResults(${JSONObject.quote(out)})")
            }
        }

        @JavascriptInterface
        fun checkUpdate() {
            lifecycleScope.launch {
                val out = try { withContext(Dispatchers.IO) { latestRelease() } } catch (e: Exception) { error(e.message ?: "no connection") }
                js("window.onUpdateInfo&&onUpdateInfo(${JSONObject.quote(out)})")
            }
        }

        @JavascriptInterface
        fun installUpdate(url: String) {
            // Only ever install files published by this app's own GitHub repository.
            if (!url.startsWith(RELEASE_DOWNLOADS)) { status("error"); return }
            if (!packageManager.canRequestPackageInstalls()) {
                status("permission")
                runOnUiThread {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
                return
            }
            lifecycleScope.launch {
                try {
                    status("downloading")
                    val apk = withContext(Dispatchers.IO) { download(url) }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", apk)
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    status("ready")
                } catch (e: Exception) {
                    status("error")
                }
            }
        }

        @JavascriptInterface
        fun sync(days: Int) {
            lifecycleScope.launch {
                val out = try {
                    readHealthConnect(days.coerceIn(1, 90))
                } catch (e: SecurityException) {
                    error("permissions")
                } catch (e: Exception) {
                    error(e.message ?: "unknown error")
                }
                js("window.onHealthSync&&onHealthSync(${JSONObject.quote(out)})")
            }
        }
    }

    private fun error(msg: String) = JSONObject().put("error", msg).toString()

    private fun status(s: String) = js("window.onUpdateStatus&&onUpdateStatus('$s')")

    private fun currentVersion(): Int =
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

    private fun latestRelease(): String {
        val c = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) return error("GitHub returned ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val latest = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
            var url = ""
            val assets = j.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            return JSONObject().put("current", currentVersion()).put("latest", latest).put("url", url).toString()
        } finally {
            c.disconnect()
        }
    }

    private fun searchFood(q: String): String {
        val url = "https://world.openfoodfacts.org/cgi/search.pl?search_simple=1&action=process&json=1&page_size=24" +
            "&sort_by=unique_scans_n&fields=product_name,brands,nutriments,serving_size,serving_quantity" +
            "&search_terms=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ActivityCalendar/1.0 (personal Android app)")
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            if (c.responseCode != 200) return error("search returned ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): File {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        try {
            if (c.responseCode != 200) throw IllegalStateException("download failed")
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        return out
    }

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/mitchcharles21/activity-calendar/releases/latest"
        private const val RELEASE_DOWNLOADS = "https://github.com/mitchcharles21/activity-calendar/releases/download/"
    }

    private suspend fun readHealthConnect(days: Int): String {
        val c = client ?: return error("unavailable")
        val granted = c.permissionController.getGrantedPermissions()
        if (granted.isEmpty()) return error("permissions")
        fun has(t: KClass<out Record>) = HealthPermission.getReadPermission(t) in granted

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays((days - 1).toLong())
        val start = from.atStartOfDay()
        val end = today.plusDays(1).atStartOfDay()
        val dayMap = mutableMapOf<String, JSONObject>()
        fun day(k: String) = dayMap.getOrPut(k) { JSONObject() }

        // Daily totals. Health Connect removes duplicates when several apps record the same steps.
        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (has(StepsRecord::class)) metrics += StepsRecord.COUNT_TOTAL
        if (has(DistanceRecord::class)) metrics += DistanceRecord.DISTANCE_TOTAL
        if (has(TotalCaloriesBurnedRecord::class)) metrics += TotalCaloriesBurnedRecord.ENERGY_TOTAL
        if (has(FloorsClimbedRecord::class)) metrics += FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
        if (has(RestingHeartRateRecord::class)) metrics += RestingHeartRateRecord.BPM_AVG
        if (metrics.isNotEmpty()) {
            val rows = c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
            for (r in rows) {
                val k = r.startTime.toLocalDate().toString()
                val a = r.result
                if (StepsRecord.COUNT_TOTAL in metrics) a[StepsRecord.COUNT_TOTAL]?.let { day(k).put("steps", it) }
                if (DistanceRecord.DISTANCE_TOTAL in metrics) a[DistanceRecord.DISTANCE_TOTAL]?.let {
                    day(k).put("dist", (it.inKilometers * 100).roundToLong() / 100.0)
                }
                if (TotalCaloriesBurnedRecord.ENERGY_TOTAL in metrics) a[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let {
                    day(k).put("cal", it.inKilocalories.roundToLong())
                }
                if (FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL in metrics) a[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let {
                    day(k).put("floors", it.roundToLong())
                }
                if (RestingHeartRateRecord.BPM_AVG in metrics) a[RestingHeartRateRecord.BPM_AVG]?.let { day(k).put("rhr", it) }
            }
        }

        // Sleep counts toward the day you wake up, matching the Google Health app.
        if (has(SleepSessionRecord::class)) {
            val awake = setOf(
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
            )
            val totals = mutableMapOf<String, Long>()
            val sStart = start.minusDays(1).atZone(zone).toInstant()
            for (s in readAll(c, SleepSessionRecord::class, sStart, end.atZone(zone).toInstant())) {
                val woke = s.endTime.atZone(zone).toLocalDate()
                if (woke.isBefore(from)) continue
                val mins = if (s.stages.isNotEmpty()) {
                    s.stages.filter { it.stage !in awake }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                } else Duration.between(s.startTime, s.endTime).toMinutes()
                totals[woke.toString()] = (totals[woke.toString()] ?: 0L) + mins
            }
            totals.forEach { (k, v) -> day(k).put("sleep", v) }
        }

        // Weight: keep the last reading of each day, in kilograms.
        val weights = JSONObject()
        if (has(WeightRecord::class)) {
            val wStart = start.atZone(zone).toInstant()
            for (w in readAll(c, WeightRecord::class, wStart, end.atZone(zone).toInstant()).sortedBy { it.time }) {
                weights.put(w.time.atZone(zone).toLocalDate().toString(), (w.weight.inKilograms * 100).roundToLong() / 100.0)
            }
        }

        val workouts = JSONArray()
        if (has(ExerciseSessionRecord::class)) {
            for (e in readAll(c, ExerciseSessionRecord::class, start.atZone(zone).toInstant(), end.atZone(zone).toInstant())) {
                val w = JSONObject()
                    .put("date", e.startTime.atZone(zone).toLocalDate().toString())
                    .put("id", e.metadata.id)
                    .put("name", e.title?.takeIf { it.isNotBlank() } ?: exerciseName(e.exerciseType))
                    .put("min", Duration.between(e.startTime, e.endTime).toMinutes())
                    .put("start", e.startTime.toString())
                val wm = mutableSetOf<AggregateMetric<*>>()
                if (has(HeartRateRecord::class)) wm += HeartRateRecord.BPM_AVG
                if (has(TotalCaloriesBurnedRecord::class)) wm += TotalCaloriesBurnedRecord.ENERGY_TOTAL
                if (wm.isNotEmpty()) {
                    try {
                        val a = c.aggregate(AggregateRequest(wm, TimeRangeFilter.between(e.startTime, e.endTime)))
                        if (HeartRateRecord.BPM_AVG in wm) a[HeartRateRecord.BPM_AVG]?.let { w.put("hr", it) }
                        if (TotalCaloriesBurnedRecord.ENERGY_TOTAL in wm) a[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let {
                            w.put("cal", it.inKilocalories.roundToLong())
                        }
                    } catch (_: Exception) {}
                }
                workouts.put(w)
            }
        }

        return JSONObject()
            .put("from", from.toString())
            .put("to", today.toString())
            .put("days", JSONObject(dayMap.toMap()))
            .put("workouts", workouts)
            .put("granted", granted.size)
            .put("weights", weights)
            .put("missing", permissions.count { it !in granted })
            .toString()
    }

    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, s: Instant, e: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val res = c.readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = TimeRangeFilter.between(s, e), pageToken = token))
            out += res.records
            token = res.pageToken
        } while (token != null)
        return out
    }

    private fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill run"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Bike"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Spin bike"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weights"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open-water swim"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Stairs"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Dance"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        else -> "Workout"
    }
}
