package com.example.xploreapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayList
import kotlin.math.sqrt
import kotlin.math.abs
import android.text.Editable
import android.text.TextWatcher
import org.w3c.dom.Text
import android.os.Parcelable
import kotlinx.parcelize.Parcelize


class ConvertActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────
    private lateinit var tvFileName     : TextView
    private lateinit var btnBack        : ImageView
    private lateinit var btnLoadGerber  : LinearLayout
    private lateinit var btnConvert     : Button
    private lateinit var btnPrint       : LinearLayout
    private lateinit var btnSave        : LinearLayout
    private lateinit var btnShare       : LinearLayout

    private lateinit var cardFile       : CardView
    private lateinit var cardSetting    : CardView
    private lateinit var cardPreview    : CardView
    private lateinit var cardGcode      : CardView
    private lateinit var cardApertures  : LinearLayout

    private lateinit var etGerber       : EditText
    private lateinit var tvGerber       : TextView
    private lateinit var tvApertures    : TextView

    private lateinit var etPenWidth2     : EditText

    private lateinit var etPenWidth     : EditText
    private lateinit var etFeedRate     : EditText
    private lateinit var etCirclePasses : EditText
    private lateinit var etCircleOffset : EditText
    private lateinit var etTraceOffset  : EditText
    private lateinit var switchMultiPass: Switch
    private lateinit var layoutTraceOffset: LinearLayout

    private lateinit var tvStats        : TextView
    private lateinit var tvGcode        : EditText
    private lateinit var previewView    : PreviewView
    private lateinit var scrollView     : ScrollView

    private lateinit var filess : TextView

    private lateinit var sendingOverlay: View
    private lateinit var sendingStatusText: TextView

    // ── State ─────────────────────────────────
    private var isConnected  = false
    private var gerberText      = ""
    private var gcodeText       = ""
    private var stateMesin      = ""
    private var currentFileName = ""
    private var fileName        = ""

    private var traces = 0
    private var flashes = 0
    private var linesGcode = 0
    private var previewLines = ArrayList<PreviewLine>()

    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let {
                try {
                    contentResolver.openOutputStream(it)?.use { stream ->
                        stream.write(gcodeText.toByteArray())
                    }

                    Toast.makeText(this, "File berhasil disimpan", Toast.LENGTH_LONG).show()

                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }


    // ═══════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_convert)

        // Status & navigation bar color
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor = getColor(R.color.abu_abu)
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.abu_abu)
        }

        // Bind views
        tvFileName      = findViewById(R.id.tv_file_name)
        btnBack         = findViewById(R.id.btn_back)
        btnLoadGerber   = findViewById(R.id.btn_choose_file)
        btnConvert      = findViewById(R.id.btn_convert_process)
        btnPrint        = findViewById(R.id.btnPrint)
        btnSave         = findViewById(R.id.btn_save)
        btnShare        = findViewById(R.id.btnShare)

        filess = findViewById(R.id.files)


        cardFile        = findViewById(R.id.file)
        cardSetting     = findViewById(R.id.setting)
        cardPreview     = findViewById(R.id.preview)
        cardGcode       = findViewById(R.id.gcode)
        cardApertures   = findViewById(R.id.cardApertures)

        etGerber        = findViewById(R.id.etGerber)
        tvGerber        = findViewById(R.id.tvGerber)
        tvApertures     = findViewById(R.id.tvApertures)

        etPenWidth      = findViewById(R.id.et_pen_width)
        etFeedRate      = findViewById(R.id.et_feed_rate)
        etCirclePasses  = findViewById(R.id.et_circle_passes)
        etCircleOffset  = findViewById(R.id.et_circle_offset)
        etTraceOffset   = findViewById(R.id.et_trace_offset)
        switchMultiPass = findViewById(R.id.switchMultiPass)
        layoutTraceOffset = findViewById(R.id.layoutTraceOffset)

        tvStats         = findViewById(R.id.tvStats)
        tvGcode         = findViewById(R.id.tv_gcode_preview)
        previewView     = findViewById(R.id.previewView)
        scrollView      = findViewById(R.id.scrollView)

        sendingOverlay   = findViewById(R.id.sendingOverlay)
        sendingStatusText = findViewById(R.id.sendingStatusText)

        // Default values
        etPenWidth.setText("0.5")
        etFeedRate.setText("60")
        etCirclePasses.setText("2")
        etCircleOffset.setText("0.25")
        etTraceOffset.setText("0.5")

        // Initial visibility
        cardPreview.visibility = View.GONE
        cardGcode.visibility   = View.GONE
        cardApertures.visibility = View.GONE

        // Prefs
        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
        stateMesin  = prefs.getString("RUNNING STATE", "Idle").toString()
        isConnected = !prefs.getString("DEVICE_ADDRESS", null).isNullOrEmpty()

        // Listeners
        btnLoadGerber.setOnClickListener   { openFilePicker() }
        btnConvert.setOnClickListener      {
            showSendingOverlay("Convert file to G-code...")
            filess.text = fileName
            convertToGcode()
        }
        btnPrint.setOnClickListener        { printGcode() }
        btnSave.setOnClickListener         { saveGcode() }
        btnShare.setOnClickListener        { shareGcode() }

        switchMultiPass.setOnCheckedChangeListener { _, checked ->
            layoutTraceOffset.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Deteksi paste manual ke etGerber
        etGerber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString() ?: return
                if (txt.length > 20 && (txt.contains("%ADD") || txt.contains("G04"))) {
                    showApertureInfo(txt)
                } else if (txt.isBlank()) {
                    cardApertures.visibility = View.GONE
                }
            }
        })

        btnBack.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)); finish()
        }
        onBackPressedDispatcher.addCallback(this) {
            startActivity(Intent(this@ConvertActivity, MainActivity::class.java)); finish()
        }
    }

    // ── File Picker ──
    private val gerberPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@registerForActivityResult
        val uri      = result.data!!.data!!
        fileName = getFileName(uri)
        val ext      = fileName.substringAfterLast('.', "").lowercase()

        if (ext !in listOf("gbr", "ger", "gtl", "gbl", "gts", "gbs", "txt", "svg")) {
            Toast.makeText(this, "Pilih file Gerber atau SVG (.gbr/.svg)", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        gerberText = readFileFromUri(uri)
        currentFileName = fileName
        tvGerber.text = fileName
        etGerber.setText(gerberText)
        showApertureInfo(gerberText, fileName)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        gerberPickerLauncher.launch(intent)
    }

    private fun showApertureInfo(text: String, fileName: String = "") {
        val isSvg = fileName.lowercase().endsWith(".svg")
                || text.trimStart().startsWith("<svg")
                || (text.trimStart().startsWith("<?xml") && text.contains("<svg", ignoreCase = true))

        if (isSvg) { showSvgInfo(text); return }

        val joined = text.replace("\r", "")
        val fmtM = Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)
        val xi   = fmtM?.groupValues?.get(1)?.toIntOrNull() ?: 2
        val xd   = fmtM?.groupValues?.get(2)?.toIntOrNull() ?: 4

        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
        val sbAp = StringBuilder()
        sbAp.appendLine("Format     : Gerber")
        var apCount = 0

        for (m in apRe.findAll(joined)) {
            val code   = "D${m.groupValues[1]}"
            val type   = m.groupValues[2]
            val params = m.groupValues[3].split("X").mapNotNull { it.toFloatOrNull() }
            apCount++

            val desc = when (type) {
                "C" -> {
                    val diam = params.getOrElse(0) { 0f }
                    val tag  = if (abs(diam - 1.524f) < 0.01f) " " else ""
                    "Circle  ⌀ %.3fmm%s".format(diam, tag)
                }
                "R" -> "Rectangle  %.3f × %.3fmm".format(
                    params.getOrElse(0) { 0f },
                    params.getOrElse(1) { params.getOrElse(0) { 0f } }
                )
                "P" -> "Polygon  ⌀%.3fmm  %d sisi  rot %.1f°".format(
                    params.getOrElse(0) { 0f },
                    params.getOrElse(1) { 0f }.toInt(),
                    params.getOrElse(2) { 0f }
                )
                else -> "type=$type  params=$params"
            }
            sbAp.appendLine("$code  $desc")
        }

        val oblongCount = countOblongPads(joined, xi, xd)
        val flashCount  = Regex("D03\\*").findAll(joined).count()
        val traceEst    = Regex("D01\\*").findAll(joined).count() - oblongCount

        sbAp.appendLine()
        sbAp.appendLine("Apertures : $apCount")
        sbAp.appendLine("Flashes   : $flashCount")
        sbAp.appendLine("Traces    : $traceEst")

        tvApertures.text = sbAp.toString().trimEnd()
        cardApertures.visibility = View.VISIBLE
    }

    private fun countOblongPads(joined: String, xi: Int, xd: Int): Int {
        val apertures = mutableMapOf<String, Pair<String, Float>>()
        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
        for (m in apRe.findAll(joined)) {
            val code   = "D${m.groupValues[1]}"
            val type   = m.groupValues[2]
            val diam   = m.groupValues[3].split("X").firstOrNull()?.toFloatOrNull() ?: 0f
            apertures[code] = Pair(type, diam)
        }

        var curAp = ""
        var curX  = 0f; var curY = 0f
        var count = 0

        for (rawLine in joined.split("\n")) {
            val line = rawLine.trim()
            val selM = Regex("^(D\\d{2,})\\*$").find(line)
            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
                curAp = selM.groupValues[1]; continue
            }
            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line) ?: continue
            val nx = if (coordM.groupValues[1].isNotEmpty()) toMM(coordM.groupValues[1], xi, xd) else curX
            val ny = if (coordM.groupValues[2].isNotEmpty()) toMM(coordM.groupValues[2], xi, xd) else curY
            val op = coordM.groupValues[3]

            if (op == "D02") { curX = nx; curY = ny }
            else if (op == "D01") {
                val ap = apertures[curAp]
                if (ap != null) {
                    val apType = ap.first; val apDiam = ap.second
                    val segLen = sqrt((nx - curX) * (nx - curX) + (ny - curY) * (ny - curY))
                    if (apType == "C" && abs(apDiam - 1.524f) < 0.01f && abs(segLen - apDiam) <= 0.01f)
                        count++
                }
                curX = nx; curY = ny
            }
        }
        return count
    }

    private fun showSvgInfo(text: String) {
        val circles   = Regex("<circle",   RegexOption.IGNORE_CASE).findAll(text).count()
        val rects     = Regex("<rect",     RegexOption.IGNORE_CASE).findAll(text).count()
        val paths     = Regex("<path",     RegexOption.IGNORE_CASE).findAll(text).count()
        val lines     = Regex("<line[^e]", RegexOption.IGNORE_CASE).findAll(text).count()
        val polylines = Regex("<polyline", RegexOption.IGNORE_CASE).findAll(text).count()
        val polygons  = Regex("<polygon",  RegexOption.IGNORE_CASE).findAll(text).count()
        val ellipses  = Regex("<ellipse",  RegexOption.IGNORE_CASE).findAll(text).count()

        val vbRe    = Regex("""viewBox=["']([^"']+)["']""")
        val vbParts = vbRe.find(text)?.groupValues?.get(1)?.trim()
            ?.split(Regex("""[\s,]+"""))?.map { it.toFloatOrNull() ?: 0f }
        val vbW = vbParts?.getOrElse(2) { 0f } ?: 0f
        val vbH = vbParts?.getOrElse(3) { 0f } ?: 0f
        val wRe = Regex("""width=["']([^"'px]+)""")
        val hRe = Regex("""height=["']([^"'px]+)""")
        val rawW = if (vbW > 0) vbW else wRe.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val rawH = if (vbH > 0) vbH else hRe.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        val MAX_W = 200f; val MAX_H = 100f
        val scale   = if (rawW > 0 && rawH > 0) kotlin.math.min(MAX_W / rawW, MAX_H / rawH) else 1f
        val scaledW = rawW * scale
        val scaledH = rawH * scale

        val sb = StringBuilder()
        sb.appendLine("Format     : SVG")
        if (rawW > 0 && rawH > 0) {
            sb.appendLine("Ukuran asli: %.1f x %.1f px".format(rawW, rawH))
            sb.appendLine("Di-scale ke: %.1f x %.1f mm  (max 200x100mm)".format(scaledW, scaledH))
            sb.appendLine("Scale factor: %.4f".format(scale))
        }
        sb.appendLine()
        sb.appendLine("Elemen terdeteksi:")
        if (circles   > 0) sb.appendLine("Circle   : $circles   -> G02 multi-pass")
        if (ellipses  > 0) sb.appendLine("Ellipse  : $ellipses  -> trace polyline")
        if (rects     > 0) sb.appendLine("Rect     : $rects     -> trace 4 sisi")
        if (lines     > 0) sb.appendLine("Line     : $lines     -> trace")
        if (polylines > 0) sb.appendLine("Polyline : $polylines -> trace")
        if (polygons  > 0) sb.appendLine("Polygon  : $polygons  -> trace")
        if (paths     > 0) sb.appendLine("Path     : $paths     -> trace (bezier/arc flatten)")

        tvApertures.text = sb.toString().trimEnd()
        cardApertures.visibility = View.VISIBLE
    }

    private fun toMM(s: String, xi: Int, xd: Int): Float {
        val neg    = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        val padded = digits.padStart(xi + xd, '0')
        val v      = "${padded.substring(0, xi)}.${padded.substring(xi)}".toFloat()
        return if (neg) -v else v
    }

    // ── Convert ──
    private fun convertToGcode() {
        gerberText = etGerber.text.toString()
        if (gerberText.isBlank()) {
            hideSendingOverlay()
            Toast.makeText(this, "Load file terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val settings = Settings(
                penWidth = etPenWidth.text.toString().toFloatOrNull() ?: 0.5f,
                feedrate = etFeedRate.text.toString().toIntOrNull()   ?: 60,
                circlePasses = etCirclePasses.text.toString().toIntOrNull()   ?: 2,
                circleOffset = etCircleOffset.text.toString().toFloatOrNull() ?: 0.25f,
                traceMultiPass = switchMultiPass.isChecked,
                traceOffset = etTraceOffset.text.toString().toFloatOrNull() ?: 0.5f
            )

            val converter = GerberConverter(settings)
            val result    = converter.convert(gerberText, currentFileName)

            gcodeText = result.gcode
            tvGcode.setText(gcodeText)

            // SIMPAN HASIL GARIS KE VARIABEL CLASS
            previewLines = ArrayList(result.previewLines)

            tvStats.text = buildString {
                if (result.isSvg) {
                    appendLine("Format      : SVG")
                    appendLine("Canvas      : ${"%.1f".format(result.svgScaledW)} x ${"%.1f".format(result.svgScaledH)} mm")
                    appendLine("---")
                }
                appendLine("Traces      : ${result.stats.traces}")
                appendLine("Flashes     : ${result.stats.flashes}")
                append    ("G-code lines: ${result.stats.gcodeLines}")
                linesGcode = result.stats.gcodeLines
                traces = result.stats.traces
                flashes = result.stats.flashes
            }

            previewView.updatePreview(result.previewLines)

            cardFile.visibility    = View.GONE
            cardSetting.visibility = View.GONE
            cardPreview.visibility = View.VISIBLE
            cardGcode.visibility   = View.GONE

            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            hideSendingOverlay()

        } catch (e: Exception) {
            hideSendingOverlay()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun saveGcode() {

        if (gcodeText.isEmpty()) {
            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
            return
        }

        if(fileName.substringAfterLast('.', "").lowercase() == "gbr") {
            fileName = fileName.removeSuffix(".gbr")
        } else {
            fileName = fileName.removeSuffix(".svg")
        }

        val ts  = SimpleDateFormat("ddMMyy_HHmm", Locale.getDefault()).format(Date())

        val suggestedName = "$fileName-$ts.gcode"

        createFileLauncher.launch(suggestedName)
    }

    private fun shareGcode() {
        if (gcodeText.isEmpty()) { Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show(); return }
        try {
            if(fileName.substringAfterLast('.', "").lowercase() == "gbr") {
                fileName = fileName.removeSuffix(".gbr")
            } else fileName = fileName.removeSuffix(".svg")
            val ts  = SimpleDateFormat("ddMMyy_HHmm", Locale.getDefault()).format(Date())
            val out = File(cacheDir, "$fileName-$ts.gcode")
            FileOutputStream(out).use { it.write(gcodeText.toByteArray()) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share G-code"))
        } catch (e: Exception) { Toast.makeText(this, "Gagal share: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun printGcode() {
        if (gcodeText.isEmpty()) {
            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show(); return
        }
        if (stateMesin == "Running") {
            Toast.makeText(this, "Printer sedang berjalan!", Toast.LENGTH_SHORT).show(); return
        }
        
        val target = if (isConnected) PrintActivity::class.java else PairingActivity::class.java
        val printIntent = Intent(this, target).apply {
            putParcelableArrayListExtra("preview_lines", previewLines)
        }

        getSharedPreferences("APP_REFF", MODE_PRIVATE).edit().putString("GCODE_LINES", linesGcode.toString()).apply()
        getSharedPreferences("APP_PREF", MODE_PRIVATE).edit().putString("EXTRA_GCODE", gcodeText).apply()
        getSharedPreferences("APP_REFF", MODE_PRIVATE).edit().putString("NAME_FILE", fileName).apply()
        getSharedPreferences("APP_REFF", MODE_PRIVATE).edit().putString("TRACES", traces.toString()).apply()
        getSharedPreferences("APP_REFF", MODE_PRIVATE).edit().putString("FLASHES", flashes.toString()).apply()
        
        startActivity(printIntent)
        finish()
    }

    private fun readFileFromUri(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("_display_name")
                    if (idx != -1) result = c.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "Unknown File"
    }

    private fun showSendingOverlay(message: String) {
        runOnUiThread {
            sendingStatusText.text    = message
            sendingOverlay.visibility = View.VISIBLE
        }
    }

    private fun updateSendingOverlayText(message: String) {
        runOnUiThread { sendingStatusText.text = message }
    }

    private fun hideSendingOverlay() {
        runOnUiThread {
            sendingOverlay.visibility = View.GONE
        }
    }
}

//package com.example.xploreapp
//
//import android.content.Intent
//import android.net.Uri
//import android.os.Build
//import android.os.Bundle
//import android.os.Environment
//import android.view.View
//import android.widget.*
//import androidx.activity.addCallback
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.appcompat.app.AppCompatActivity
//import androidx.cardview.widget.CardView
//import androidx.core.content.FileProvider
//import java.io.File
//import java.io.FileOutputStream
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//import kotlin.math.sqrt
//import kotlin.math.abs
//import android.text.Editable
//import android.text.TextWatcher
//
//class ConvertActivity : AppCompatActivity() {
//
//    // ── Views ─────────────────────────────────
//    private lateinit var tvFileName     : TextView
//    private lateinit var btnBack        : ImageView
//    private lateinit var btnLoadGerber  : LinearLayout
//    private lateinit var btnConvert     : Button
//    private lateinit var btnPrint       : LinearLayout
//    private lateinit var btnSave        : LinearLayout
//    private lateinit var btnShare       : LinearLayout
//
//    private lateinit var cardFile       : CardView
//    private lateinit var cardSetting    : CardView
//    private lateinit var cardPreview    : CardView
//    private lateinit var cardGcode      : CardView
//    private lateinit var cardApertures  : LinearLayout
//
//    private lateinit var etGerber       : EditText
//    private lateinit var tvGerber       : TextView
//    private lateinit var tvApertures    : TextView
//
//    private lateinit var etPenWidth     : EditText
//    private lateinit var etFeedRate     : EditText
//    private lateinit var etCirclePasses : EditText
//    private lateinit var etCircleOffset : EditText
//    private lateinit var etTraceOffset  : EditText
//    private lateinit var switchMultiPass: Switch
//    private lateinit var layoutTraceOffset: LinearLayout
//
//    private lateinit var tvStats        : TextView
//    private lateinit var tvGcode        : EditText
//    private lateinit var previewView    : PreviewView
//    private lateinit var scrollView     : ScrollView
//
//    // ── State ─────────────────────────────────
//    private var isConnected  = false
//    private var gerberText   = ""
//    private var gcodeText    = ""
//    private var stateMesin   = ""
//
//
//
//    // ═══════════════════════════════════════════
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_convert)
//
//        // Status & navigation bar color (non-deprecated approach)
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
//            @Suppress("DEPRECATION")
//            window.statusBarColor = getColor(R.color.abu_abu)
//            @Suppress("DEPRECATION")
//            window.navigationBarColor = getColor(R.color.abu_abu)
//        }
//
//        // Bind views
//        tvFileName      = findViewById(R.id.tv_file_name)
//        btnBack         = findViewById(R.id.btn_back)
//        btnLoadGerber   = findViewById(R.id.btn_choose_file)
//        btnConvert      = findViewById(R.id.btn_convert_process)
//        btnPrint        = findViewById(R.id.btnPrint)
//        btnSave         = findViewById(R.id.btn_save)
//        btnShare        = findViewById(R.id.btnShare)
//
//        cardFile        = findViewById(R.id.file)
//        cardSetting     = findViewById(R.id.setting)
//        cardPreview     = findViewById(R.id.preview)
//        cardGcode       = findViewById(R.id.gcode)
//        cardApertures   = findViewById(R.id.cardApertures)
//
//        etGerber        = findViewById(R.id.etGerber)
//        tvGerber        = findViewById(R.id.tvGerber)
//        tvApertures     = findViewById(R.id.tvApertures)
//
//        etPenWidth      = findViewById(R.id.et_pen_width)
//        etFeedRate      = findViewById(R.id.et_feed_rate)
//        etCirclePasses  = findViewById(R.id.et_circle_passes)
//        etCircleOffset  = findViewById(R.id.et_circle_offset)
//        etTraceOffset   = findViewById(R.id.et_trace_offset)
//        switchMultiPass = findViewById(R.id.switchMultiPass)
//        layoutTraceOffset = findViewById(R.id.layoutTraceOffset)
//
//        tvStats         = findViewById(R.id.tvStats)
//        tvGcode         = findViewById(R.id.tv_gcode_preview)
//        previewView     = findViewById(R.id.previewView)
//        scrollView      = findViewById(R.id.scrollView)
//
//        // Default values
//        etPenWidth.setText("0.5")
//        etFeedRate.setText("60")
//        etCirclePasses.setText("2")
//        etCircleOffset.setText("0.25")
//        etTraceOffset.setText("0.5")
//
//        // Initial visibility
//        cardPreview.visibility = View.GONE
//        cardGcode.visibility   = View.GONE
//        cardApertures.visibility = View.GONE
//
//        // Prefs
//        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
//        stateMesin  = prefs.getString("RUNNING STATE", "Idle").toString()
//        isConnected = !prefs.getString("DEVICE_ADDRESS", null).isNullOrEmpty()
//
//        // Listeners
//        btnLoadGerber.setOnClickListener   { openFilePicker() }
//        btnConvert.setOnClickListener      { convertToGcode() }
//        btnPrint.setOnClickListener        { printGcode() }
//        btnSave.setOnClickListener         { saveGcode() }
//        btnShare.setOnClickListener        { shareGcode() }
//
//        switchMultiPass.setOnCheckedChangeListener { _, checked ->
//            layoutTraceOffset.visibility = if (checked) View.VISIBLE else View.GONE
//        }
//
//        // Deteksi paste manual ke etGerber
//        etGerber.addTextChangedListener(object : TextWatcher {
//            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
//            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
//            override fun afterTextChanged(s: Editable?) {
//                val txt = s?.toString() ?: return
//                // Hanya proses jika konten terlihat seperti gerber (ada %ADD atau G04)
//                if (txt.length > 20 && (txt.contains("%ADD") || txt.contains("G04"))) {
//                    showApertureInfo(txt)
//                } else if (txt.isBlank()) {
//                    cardApertures.visibility = View.GONE
//                }
//            }
//        })
//
//        btnBack.setOnClickListener {
//            startActivity(Intent(this, MainActivity::class.java)); finish()
//        }
//        onBackPressedDispatcher.addCallback(this) {
//            startActivity(Intent(this@ConvertActivity, MainActivity::class.java)); finish()
//        }
//    }
//
//    // ── File Picker (ActivityResultLauncher) ──
//    private val gerberPickerLauncher = registerForActivityResult(
//        ActivityResultContracts.StartActivityForResult()
//    ) { result ->
//        if (result.resultCode != RESULT_OK || result.data?.data == null) return@registerForActivityResult
//        val uri      = result.data!!.data!!
//        val fileName = getFileName(uri)
//        val ext      = fileName.substringAfterLast('.', "").lowercase()
//
//        if (ext !in listOf("gbr", "ger", "gtl", "gbl", "gts", "gbs", "txt")) {
//            Toast.makeText(this, "Pilih file Gerber (.gbr)", Toast.LENGTH_SHORT).show()
//            return@registerForActivityResult
//        }
//        gerberText = readFileFromUri(uri)
//        tvGerber.text = fileName
//        etGerber.setText(gerberText)
//        Toast.makeText(this, "Loaded: $fileName", Toast.LENGTH_SHORT).show()
//        showApertureInfo(gerberText)
//    }
//
//    private fun openFilePicker() {
//        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
//            addCategory(Intent.CATEGORY_OPENABLE)
//            type = "*/*"
//        }
//        gerberPickerLauncher.launch(intent)
//    }
//
//    // ── Parse & tampilkan aperture ────────────
//    private fun showApertureInfo(text: String) {
//        val joined = text.replace("\r", "")
//
//        // Baca format spec
//        val fmtM = Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)
//        val xi   = fmtM?.groupValues?.get(1)?.toIntOrNull() ?: 2
//        val xd   = fmtM?.groupValues?.get(2)?.toIntOrNull() ?: 4
//
//        // Parse apertures
//        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
//        val sbAp = StringBuilder()
//        var apCount = 0
//
//        for (m in apRe.findAll(joined)) {
//            val code   = "D${m.groupValues[1]}"
//            val type   = m.groupValues[2]
//
//            val params = m.groupValues[3].split("X").mapNotNull { it.toFloatOrNull() }
//            apCount++
//
//            val desc = when (type) {
//                "C" -> {
//                    val diam = params.getOrElse(0) { 0f }
//                    val tag  = if (abs(diam - 1.524f) < 0.01f) " ← header pads" else ""
//                    "Circle  ⌀ %.3fmm%s".format(diam, tag)
//                }
//                "R" -> "Rectangle  %.3f × %.3fmm".format(
//                    params.getOrElse(0) { 0f },
//                    params.getOrElse(1) { params.getOrElse(0) { 0f } }
//                )
//                "P" -> "Polygon  ⌀%.3fmm  %d sisi  rot %.1f°".format(
//                    params.getOrElse(0) { 0f },
//                    params.getOrElse(1) { 0f }.toInt(),
//                    params.getOrElse(2) { 0f }
//                )
//                else -> "type=$type  params=$params"
//            }
//            sbAp.appendLine("$code  $desc")
//        }
//
//        // Hitung oblong pads dan trace biasa
//        val oblongCount = countOblongPads(joined, xi, xd)
//        val flashCount  = Regex("D03\\*").findAll(joined).count()
//        val traceEst    = Regex("D01\\*").findAll(joined).count() - oblongCount
//
//        sbAp.appendLine()
//        sbAp.appendLine("Apertures : $apCount")
//        sbAp.appendLine("Flashes   : $flashCount")
//        sbAp.appendLine("Header pads: $oblongCount")
//        sbAp.appendLine("Traces    : $traceEst")
//
//        tvApertures.text = sbAp.toString().trimEnd()
//        cardApertures.visibility = View.VISIBLE
//    }
//
//    private fun countOblongPads(joined: String, xi: Int, xd: Int): Int {
//        val apertures = mutableMapOf<String, Pair<String, Float>>() // code → (type, diameter)
//        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
//        for (m in apRe.findAll(joined)) {
//            val code   = "D${m.groupValues[1]}"
//            val type   = m.groupValues[2]
//            val diam   = m.groupValues[3].split("X").firstOrNull()?.toFloatOrNull() ?: 0f
//            apertures[code] = Pair(type, diam)
//        }
//
//        var curAp = ""
//        var curX  = 0f; var curY = 0f
//        var count = 0
//
//        for (rawLine in joined.split("\n")) {
//            val line = rawLine.trim()
//            val selM = Regex("^(D\\d{2,})\\*$").find(line)
//            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
//                curAp = selM.groupValues[1]; continue
//            }
//            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line) ?: continue
//            val nx = if (coordM.groupValues[1].isNotEmpty()) toMM(coordM.groupValues[1], xi, xd) else curX
//            val ny = if (coordM.groupValues[2].isNotEmpty()) toMM(coordM.groupValues[2], xi, xd) else curY
//            val op = coordM.groupValues[3]
//
//            if (op == "D02") { curX = nx; curY = ny }
//            else if (op == "D01") {
//                val ap = apertures[curAp]
//                if (ap != null) {
//                    val apType = ap.first; val apDiam = ap.second
//                    val segLen = sqrt((nx - curX) * (nx - curX) + (ny - curY) * (ny - curY))
//                    if (apType == "C" && abs(apDiam - 1.524f) < 0.01f && abs(segLen - apDiam) <= 0.01f)
//                        count++
//                }
//                curX = nx; curY = ny
//            }
//        }
//        return count
//    }
//
//    private fun toMM(s: String, xi: Int, xd: Int): Float {
//        val neg    = s.startsWith("-")
//        val digits = if (neg) s.substring(1) else s
//        val padded = digits.padStart(xi + xd, '0')
//        val v      = "${padded.substring(0, xi)}.${padded.substring(xi)}".toFloat()
//        return if (neg) -v else v
//    }
//
//    // ── Convert ───────────────────────────────
//    private fun convertToGcode() {
//        gerberText = etGerber.text.toString()
//        if (gerberText.isBlank()) {
//            Toast.makeText(this, "Load file Gerber terlebih dahulu!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        try {
//            val settings = Settings(
//                penWidth = etPenWidth.text.toString().toFloatOrNull() ?: 0.5f,
//                feedrate = etFeedRate.text.toString().toIntOrNull()   ?: 60,
//                circlePasses = etCirclePasses.text.toString().toIntOrNull()   ?: 2,
//                circleOffset = etCircleOffset.text.toString().toFloatOrNull() ?: 0.25f,
//                traceMultiPass = switchMultiPass.isChecked,
//                traceOffset = etTraceOffset.text.toString().toFloatOrNull() ?: 0.5f
//            )
//
//            val converter = GerberConverter(settings)
//            val result    = converter.convert(gerberText)
//
//            gcodeText = result.gcode
//            tvGcode.setText(gcodeText)
//
//            tvStats.text = buildString {
//                appendLine("Traces      : ${result.stats.traces}")
//                appendLine("Flashes     : ${result.stats.flashes}")
//                append    ("G-code lines: ${result.stats.gcodeLines}")
//            }
//
//            previewView.updatePreview(result.previewLines)
//
//            cardFile.visibility    = View.GONE
//            cardSetting.visibility = View.GONE
//            cardApertures.visibility = View.GONE
//            cardPreview.visibility = View.VISIBLE
//            cardGcode.visibility   = View.GONE
//
//            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
//            Toast.makeText(this, "Konversi berhasil!", Toast.LENGTH_SHORT).show()
//
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
//            e.printStackTrace()
//        }
//    }
//
//    // ── Save / Share / Print ──────────────────
//    private fun saveGcode() {
//        if (gcodeText.isEmpty()) { Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show(); return }
//        try {
//            val ts  = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.getDefault()).format(Date())
//            val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Gcode-$ts.gcode")
//            FileOutputStream(out).use { it.write(gcodeText.toByteArray()) }
//            Toast.makeText(this, "Tersimpan di Downloads/Gcode-$ts.gcode", Toast.LENGTH_LONG).show()
//        } catch (e: Exception) { Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show() }
//    }
//
//    private fun shareGcode() {
//        if (gcodeText.isEmpty()) { Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show(); return }
//        try {
//            val out = File(cacheDir, "output.gcode")
//            FileOutputStream(out).use { it.write(gcodeText.toByteArray()) }
//            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
//            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
//                type = "text/plain"
//                putExtra(Intent.EXTRA_STREAM, uri)
//                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//            }, "Share G-code"))
//        } catch (e: Exception) { Toast.makeText(this, "Gagal share: ${e.message}", Toast.LENGTH_LONG).show() }
//    }
//
//    private fun printGcode() {
//        if (gcodeText.isEmpty()) { Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show(); return }
//        if (stateMesin == "Running") { Toast.makeText(this, "Printer sedang berjalan!", Toast.LENGTH_SHORT).show(); return }
//        val target = if (isConnected) PrintActivity::class.java else PairingActivity::class.java
//        getSharedPreferences("APP_PREF", MODE_PRIVATE).edit().putString("EXTRA_GCODE", gcodeText).apply()
//        startActivity(Intent(this, target)); finish()
//    }
//
//    // ── Utils ─────────────────────────────────
//    private fun readFileFromUri(uri: Uri): String =
//        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
//
//    private fun getFileName(uri: Uri): String {
//        var result: String? = null
//        if (uri.scheme == "content") {
//            contentResolver.query(uri, null, null, null, null)?.use { c ->
//                if (c.moveToFirst()) {
//                    val idx = c.getColumnIndex("_display_name")
//                    if (idx != -1) result = c.getString(idx)
//                }
//            }
//        }
//        if (result == null) {
//            result = uri.path
//            val cut = result?.lastIndexOf('/')
//            if (cut != null && cut != -1) result = result?.substring(cut + 1)
//        }
//        return result ?: "Unknown File"
//    }
//}
//package com.example.xploreapp
//
//import android.app.Activity
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.os.Environment
//import android.view.View
//import android.widget.Button
//import android.widget.EditText
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ScrollView
//import android.widget.TextView
//import android.widget.Toast
//import androidx.activity.addCallback
//import androidx.appcompat.app.AppCompatActivity
//import androidx.cardview.widget.CardView
//import androidx.core.content.FileProvider
//import java.io.File
//import java.io.FileOutputStream
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//
//class ConvertActivity : AppCompatActivity() {
//
//    private lateinit var tvFileName: TextView
//    private lateinit var btnBack: ImageView
//    private lateinit var btnLoadGerber: LinearLayout
//    private lateinit var btnConvert: Button
//    private lateinit var btnPrint: LinearLayout
//    private lateinit var btnSave: LinearLayout
//    private lateinit var btnShare: LinearLayout
//    private lateinit var file: CardView
//    private lateinit var setting: CardView
//    private lateinit var preview: CardView
//    private lateinit var gcode: CardView
//
//    private lateinit var etGerber: EditText
//    private lateinit var etPenWidth: EditText
//    private lateinit var etFeedRate: EditText
//    private lateinit var tvStats: TextView
//    private lateinit var tvGcode: TextView
//    private lateinit var previewView: PreviewView
//    private lateinit var scrollView: ScrollView
//    private lateinit var tvGerber: TextView
//
//    private var isConnected = false
//    private var gerberText = ""
//    private var gcodeText = ""
//    private var stateMesin = ""
//
//    private val PICK_GERBER_FILE = 1
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_convert)
//
//        window.statusBarColor = getColor(R.color.abu_abu)
//        window.navigationBarColor = getColor(R.color.abu_abu)
//
//        tvFileName   = findViewById(R.id.tv_file_name)
//        btnBack      = findViewById(R.id.btn_back)
//        btnLoadGerber = findViewById(R.id.btn_choose_file)
//        btnConvert   = findViewById(R.id.btn_convert_process)
//        btnPrint     = findViewById(R.id.btnPrint)
//        btnSave      = findViewById(R.id.btn_save)
//        btnShare     = findViewById(R.id.btnShare)
//
//        etGerber     = findViewById(R.id.etGerber)
//        etPenWidth   = findViewById(R.id.et_pen_width)
//        etFeedRate   = findViewById(R.id.et_feed_rate)
//        tvStats      = findViewById(R.id.tvStats)
//        tvGcode      = findViewById(R.id.tv_gcode_preview)
//        previewView  = findViewById(R.id.previewView)
//        scrollView   = findViewById(R.id.scrollView)
//        tvGerber     = findViewById(R.id.tvGerber)
//
//        file         = findViewById(R.id.file)
//        setting      = findViewById(R.id.setting)
//        preview      = findViewById(R.id.preview)
//        gcode        = findViewById(R.id.gcode)
//
//        file.visibility = View.VISIBLE
//        setting.visibility = View.VISIBLE
//        preview.visibility = View.GONE
//        gcode.visibility = View.GONE
//
//        // Default values
//        etFeedRate.setText("60")
//        etPenWidth.setText("0.5")
//
//        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
//        val deviceAddress = prefs.getString("DEVICE_ADDRESS", null)
//        stateMesin = prefs.getString("RUNNING STATE", "Idle").toString()
//        isConnected = !deviceAddress.isNullOrEmpty()
//
//        btnLoadGerber.setOnClickListener { openFilePicker() }
//        btnConvert.setOnClickListener    { convertToGcode() }
//        btnPrint.setOnClickListener      { printGcode() }
//        btnSave.setOnClickListener       { saveGcode() }
//        btnShare.setOnClickListener      { shareGcode() }
//
//        btnBack.setOnClickListener {
//            startActivity(Intent(this, MainActivity::class.java))
//            finish()
//        }
//
//        onBackPressedDispatcher.addCallback(this) {
//            startActivity(Intent(this@ConvertActivity, MainActivity::class.java))
//            finish()
//        }
//    }
//
//    private fun openFilePicker() {
//        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
//            addCategory(Intent.CATEGORY_OPENABLE)
//            type = "*/*"
//        }
//        startActivityForResult(intent, PICK_GERBER_FILE)
//    }
//
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//        if (resultCode != RESULT_OK || data?.data == null) return
//
//        val uri = data.data!!
//        val fileName = getFileName(uri)
//        val ext = fileName.substringAfterLast('.', "").lowercase()
//
//        if (requestCode == PICK_GERBER_FILE) {
//            if (ext !in listOf("gbr", "ger", "gtl", "gbl", "gts", "gbs", "txt")) {
//                Toast.makeText(this, "Pilih file Gerber (.gbr)", Toast.LENGTH_SHORT).show()
//                return
//            }
//            gerberText = readFileFromUri(uri)
//            tvGerber.text = fileName
//            etGerber.setText(gerberText)
//            Toast.makeText(this, "Gerber loaded: $fileName", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    private fun convertToGcode() {
//        gerberText = etGerber.text.toString()
//
//        if (gerberText.isBlank()) {
//            Toast.makeText(this, "Load file Gerber terlebih dahulu!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        try {
//            val settings = Settings(
//                penWidth = etPenWidth.text.toString().toFloatOrNull() ?: 0.5f,
//                feedrate = etFeedRate.text.toString().toIntOrNull() ?: 60
//            )
//
//            val converter = GerberConverter(settings)
//            val result    = converter.convert(gerberText)
//
//            gcodeText = result.gcode
//            tvGcode.text = gcodeText
//
//            // Stats
//            tvStats.text = """
//                Traces    : ${result.stats.traces}
//                Flashes   : ${result.stats.flashes}
//                G-code lines: ${result.stats.gcodeLines}
//            """.trimIndent()
//
//            // Preview
//            previewView.updatePreview(result.previewLines)
//
//            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
//            Toast.makeText(this, "Konversi berhasil!", Toast.LENGTH_SHORT).show()
//            file.visibility = View.GONE
//            setting.visibility = View.GONE
//            preview.visibility = View.VISIBLE
//            gcode.visibility = View.VISIBLE
//
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
//            e.printStackTrace()
//        }
//    }
//
//    private fun saveGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        try {
//            val ts = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.getDefault()).format(Date())
//            val file = File(
//                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
//                "Gcode-$ts.gcode"
//            )
//            FileOutputStream(file).use { it.write(gcodeText.toByteArray()) }
//            Toast.makeText(this, "Tersimpan di Downloads/Gcode-$ts.gcode", Toast.LENGTH_LONG).show()
//        } catch (e: Exception) {
//            Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//    private fun shareGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        try {
//            val file = File(cacheDir, "output.gcode")
//            FileOutputStream(file).use { it.write(gcodeText.toByteArray()) }
//            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
//            startActivity(Intent.createChooser(
//                Intent(Intent.ACTION_SEND).apply {
//                    type = "text/plain"
//                    putExtra(Intent.EXTRA_STREAM, uri)
//                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                }, "Share G-code"
//            ))
//        } catch (e: Exception) {
//            Toast.makeText(this, "Gagal share: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//    private fun printGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (stateMesin == "Running") {
//            Toast.makeText(this, "Printer sedang berjalan!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        val target = if (isConnected) PrintActivity::class.java else PairingActivity::class.java
//        getSharedPreferences("APP_PREF", MODE_PRIVATE).edit()
//            .putString("EXTRA_GCODE", gcodeText).apply()
//        startActivity(Intent(this, target))
//        finish()
//    }
//
//    private fun readFileFromUri(uri: Uri): String =
//        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
//
//    private fun getFileName(uri: Uri): String {
//        var result: String? = null
//        if (uri.scheme == "content") {
//            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
//                if (cursor.moveToFirst()) {
//                    val idx = cursor.getColumnIndex("_display_name")
//                    if (idx != -1) result = cursor.getString(idx)
//                }
//            }
//        }
//        if (result == null) {
//            result = uri.path
//            val cut = result?.lastIndexOf('/')
//            if (cut != null && cut != -1) result = result?.substring(cut + 1)
//        }
//        return result ?: "Unknown File"
//    }
//}


//package com.example.xploreapp
//
//import android.app.Activity
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.os.Environment
//import android.view.View
//import android.widget.Button
//import android.widget.EditText
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ScrollView
//import android.widget.TextView
//import android.widget.Toast
//import androidx.activity.addCallback
//import androidx.appcompat.app.AppCompatActivity
//import androidx.cardview.widget.CardView
//import androidx.core.content.FileProvider
//import java.io.File
//import java.io.FileOutputStream
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//
//class ConvertActivity : AppCompatActivity() {
//
//    private lateinit var tvFileName: TextView
//    private lateinit var btnBack: ImageView
//    private lateinit var btnLoadGerber: LinearLayout
//    private lateinit var btnConvert: Button
//    private lateinit var btnPrint: LinearLayout
//    private lateinit var btnSave: LinearLayout
//    private lateinit var btnShare: LinearLayout
//    private lateinit var file: CardView
//    private lateinit var setting: CardView
//    private lateinit var preview: CardView
//    private lateinit var gcode: CardView
//
//    private lateinit var etGerber: EditText
//    private lateinit var etPenWidth: EditText
//    private lateinit var etFeedRate: EditText
//    private lateinit var tvStats: TextView
//    private lateinit var tvGcode: TextView
//    private lateinit var previewView: PreviewView
//    private lateinit var scrollView: ScrollView
//    private lateinit var tvGerber: TextView
//
//    private var isConnected = false
//    private var gerberText = ""
//    private var gcodeText = ""
//    private var stateMesin = ""
//
//    private val PICK_GERBER_FILE = 1
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_convert)
//
//        window.statusBarColor = getColor(R.color.abu_abu)
//        window.navigationBarColor = getColor(R.color.abu_abu)
//
//        tvFileName   = findViewById(R.id.tv_file_name)
//        btnBack      = findViewById(R.id.btn_back)
//        btnLoadGerber = findViewById(R.id.btn_choose_file)
//        btnConvert   = findViewById(R.id.btn_convert_process)
//        btnPrint     = findViewById(R.id.btnPrint)
//        btnSave      = findViewById(R.id.btn_save)
//        btnShare     = findViewById(R.id.btnShare)
//
//        etGerber     = findViewById(R.id.etGerber)
//        etPenWidth   = findViewById(R.id.et_pen_width)
//        etFeedRate   = findViewById(R.id.et_feed_rate)
//        tvStats      = findViewById(R.id.tvStats)
//        tvGcode      = findViewById(R.id.tv_gcode_preview)
//        previewView  = findViewById(R.id.previewView)
//        scrollView   = findViewById(R.id.scrollView)
//        tvGerber     = findViewById(R.id.tvGerber)
//
//        file         = findViewById(R.id.file)
//        setting      = findViewById(R.id.setting)
//        preview      = findViewById(R.id.preview)
//        gcode        = findViewById(R.id.gcode)
//
//        file.visibility = View.VISIBLE
//        setting.visibility = View.VISIBLE
//        preview.visibility = View.GONE
//        gcode.visibility = View.GONE
//
//
//        // Default values
//        etFeedRate.setText("60")
//        etPenWidth.setText("0.5")
//
//        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
//        val deviceAddress = prefs.getString("DEVICE_ADDRESS", null)
//        stateMesin = prefs.getString("RUNNING STATE", "Idle").toString()
//        isConnected = !deviceAddress.isNullOrEmpty()
//
//        btnLoadGerber.setOnClickListener { openFilePicker() }
//        btnConvert.setOnClickListener    { convertToGcode() }
//        btnPrint.setOnClickListener      { printGcode() }
//        btnSave.setOnClickListener       { saveGcode() }
//        btnShare.setOnClickListener      { shareGcode() }
//
//        btnBack.setOnClickListener {
//            startActivity(Intent(this, MainActivity::class.java))
//            finish()
//        }
//
//        onBackPressedDispatcher.addCallback(this) {
//            startActivity(Intent(this@ConvertActivity, MainActivity::class.java))
//            finish()
//        }
//    }
//
//    private fun openFilePicker() {
//        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
//            addCategory(Intent.CATEGORY_OPENABLE)
//            type = "*/*"
//        }
//        startActivityForResult(intent, PICK_GERBER_FILE)
//    }
//
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//        if (resultCode != RESULT_OK || data?.data == null) return
//
//        val uri = data.data!!
//        val fileName = getFileName(uri)
//        val ext = fileName.substringAfterLast('.', "").lowercase()
//
//        if (requestCode == PICK_GERBER_FILE) {
//            if (ext !in listOf("gbr", "ger", "gtl", "gbl", "gts", "gbs", "txt")) {
//                Toast.makeText(this, "Pilih file Gerber (.gbr)", Toast.LENGTH_SHORT).show()
//                return
//            }
//            gerberText = readFileFromUri(uri)
//            tvGerber.text = fileName
//            etGerber.setText(gerberText)
//            Toast.makeText(this, "Gerber loaded: $fileName", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    private fun convertToGcode() {
//        gerberText = etGerber.text.toString()
//
//        if (gerberText.isBlank()) {
//            Toast.makeText(this, "Load file Gerber terlebih dahulu!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        try {
//            val settings = Settings(
//                penWidth = etPenWidth.text.toString().toFloatOrNull() ?: 0.5f,
//                feedrate = etFeedRate.text.toString().toIntOrNull() ?: 60
//            )
//
//            val converter = GerberConverter(settings)
//            val result    = converter.convert(gerberText)
//
//            gcodeText = result.gcode
//            tvGcode.text = gcodeText
//
//            // Stats
//            tvStats.text = """
//                Traces    : ${result.stats.traces}
//                Flashes   : ${result.stats.flashes}
//                G-code lines: ${result.stats.gcodeLines}
//            """.trimIndent()
//
//            // Preview
//            previewView.updatePreview(result.traces, result.flashes)
//
//            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
//            Toast.makeText(this, "Konversi berhasil!", Toast.LENGTH_SHORT).show()
//            file.visibility = View.GONE
//            setting.visibility = View.GONE
//            preview.visibility = View.VISIBLE
//            gcode.visibility = View.VISIBLE
//
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
//            e.printStackTrace()
//        }
//    }
//
//    private fun saveGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        try {
//            val ts = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.getDefault()).format(Date())
//            val file = File(
//                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
//                "Gcode-$ts.gcode"
//            )
//            FileOutputStream(file).use { it.write(gcodeText.toByteArray()) }
//            Toast.makeText(this, "Tersimpan di Downloads/Gcode-$ts.gcode", Toast.LENGTH_LONG).show()
//        } catch (e: Exception) {
//            Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//    private fun shareGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        try {
//            val file = File(cacheDir, "output.gcode")
//            FileOutputStream(file).use { it.write(gcodeText.toByteArray()) }
//            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
//            startActivity(Intent.createChooser(
//                Intent(Intent.ACTION_SEND).apply {
//                    type = "text/plain"
//                    putExtra(Intent.EXTRA_STREAM, uri)
//                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                }, "Share G-code"
//            ))
//        } catch (e: Exception) {
//            Toast.makeText(this, "Gagal share: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//    private fun printGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "Tidak ada G-code!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (stateMesin == "Running") {
//            Toast.makeText(this, "Printer sedang berjalan!", Toast.LENGTH_SHORT).show()
//            return
//        }
//        val target = if (isConnected) PrintActivity::class.java else PairingActivity::class.java
//        getSharedPreferences("APP_PREF", MODE_PRIVATE).edit()
//            .putString("EXTRA_GCODE", gcodeText).apply()
//        startActivity(Intent(this, target))
//        finish()
//    }
//
//    private fun readFileFromUri(uri: Uri): String =
//        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
//
//    private fun getFileName(uri: Uri): String {
//        var result: String? = null
//        if (uri.scheme == "content") {
//            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
//                if (cursor.moveToFirst()) {
//                    val idx = cursor.getColumnIndex("_display_name")
//                    if (idx != -1) result = cursor.getString(idx)
//                }
//            }
//        }
//        if (result == null) {
//            result = uri.path
//            val cut = result?.lastIndexOf('/')
//            if (cut != null && cut != -1) result = result?.substring(cut + 1)
//        }
//        return result ?: "Unknown File"
//    }
//}


//package com.example.xploreapp
//
//import android.app.Activity
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.os.Environment
//import android.view.View
//import android.widget.Button
//import android.widget.EditText
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ScrollView
//import android.widget.TextView
//import android.widget.Toast
//import androidx.activity.addCallback
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.content.FileProvider
//import java.io.File
//import java.io.FileOutputStream
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//
//class ConvertActivity : AppCompatActivity() {
//
//    private lateinit var tvFileName: TextView
//
//
//    private lateinit var btnBack: ImageView
//    private lateinit var btnLoadGerber: LinearLayout
//    private lateinit var btnLoadDrill: LinearLayout
//    private lateinit var btnConvert: Button
//    private lateinit var btnPrint: LinearLayout
//    private lateinit var btnSave: LinearLayout
//    private lateinit var btnShare: LinearLayout
//
//    private lateinit var etGerber: EditText
//    private lateinit var etDrill: EditText
//    private lateinit var etPenWidth: EditText
//    private lateinit var etFeedRate: EditText
//    private lateinit var tvStats: TextView
//    private lateinit var tvGcode: TextView
//    private lateinit var previewView: PreviewView
//    private lateinit var scrollView: ScrollView
//    private lateinit var tvGerber: TextView
//    private lateinit var tvDrill: TextView
//
//
//
//    private var isConnected = false
//
//    private var gerberText = ""
//    private var drillText = ""
//    private var gcodeText = ""
//    private var stateMesin = ""
//
//    private val PICK_GERBER_FILE = 1
//    private val PICK_DRILL_FILE = 2
//
////    private val filePickerLauncher = registerForActivityResult(
////        ActivityResultContracts.StartActivityForResult()
////    ) { result ->
////        if (result.resultCode == Activity.RESULT_OK) {
////            val data: Intent? = result.data
////            val uri: Uri? = data?.data
////            uri?.let {
////                val fileName = getFileName(it)
////                tvFileName.text = fileName
////            }
////        }
////    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_convert)
//
//        window.statusBarColor = getColor(R.color.abu_abu)
//        window.navigationBarColor = getColor(R.color.abu_abu)
//
//        tvFileName = findViewById(R.id.tv_file_name)
//        btnBack = findViewById(R.id.btn_back)
//
//        btnLoadGerber = findViewById(R.id.btn_choose_file)
//        btnLoadDrill = findViewById(R.id.btn_choose_file_Drill)
//        btnConvert = findViewById(R.id.btn_convert_process)
//        btnPrint = findViewById(R.id.btnPrint)
//        btnSave = findViewById(R.id.btn_save)
//        btnShare = findViewById(R.id.btnShare)
//
//        etGerber = findViewById(R.id.etGerber)
//        etDrill = findViewById(R.id.etDrill)
//        etPenWidth = findViewById(R.id.et_pen_width)
//        etFeedRate = findViewById(R.id.et_feed_rate)
//        tvStats = findViewById(R.id.tvStats)
//        tvGcode = findViewById(R.id.tv_gcode_preview)
//        previewView = findViewById(R.id.previewView)
//        scrollView = findViewById(R.id.scrollView)
//        tvGerber = findViewById(R.id.tvGerber)
//        tvDrill = findViewById(R.id.tvDrill)
//
//        etFeedRate.setText("1000")
//        etPenWidth.setText("0.5")
//
//        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
//        val deviceAddress = prefs.getString("DEVICE_ADDRESS", null)
//
//        stateMesin = prefs.getString("RUNNING STATE", "Idle").toString()
//        isConnected = !deviceAddress.isNullOrEmpty()
//
//        btnLoadGerber.setOnClickListener {
//            openFilePicker(PICK_GERBER_FILE)
//        }
//
//        btnLoadDrill.setOnClickListener {
//            openFilePicker(PICK_DRILL_FILE)
//        }
//
//        btnConvert.setOnClickListener {
//            convertToGcode()
//        }
//
//        btnPrint.setOnClickListener {
//            printGcode()
//        }
//
//        btnSave.setOnClickListener {
//            saveGcode()
//        }
//
//        btnShare.setOnClickListener {
//            shareGcode()
//        }
//
//
//        btnBack.setOnClickListener {
//            val intent = Intent(this, MainActivity::class.java)
//            startActivity(intent)
//            finish()
//        }
//
//        onBackPressedDispatcher.addCallback(this) {
//            val intent = Intent(this@ConvertActivity, MainActivity::class.java)
//            startActivity(intent)
//            finish()
//        }
//    }
//
////    private fun openFilePicker() {
////        val intent = Intent(Intent.ACTION_GET_CONTENT)
////        intent.type = "*/*" // Mengizinkan semua jenis file, bisa diubah ke ".gbr" jika perlu
////        intent.addCategory(Intent.CATEGORY_OPENABLE)
////        filePickerLauncher.launch(intent)
////    }
//
//    private fun openFilePicker(requestCode: Int) {
//        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
//            addCategory(Intent.CATEGORY_OPENABLE)
//            type = "*/*"
//        }
//        startActivityForResult(intent, requestCode)
//    }
//
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//
//        if (resultCode == RESULT_OK && data?.data != null) {
//            val uri = data.data!!
//
//            val fileName = getFileName(uri)
//            val content = readFileFromUri(uri)
//
//            val ext = fileName.substringAfterLast('.', "").lowercase()
//
//            when (requestCode) {
//                PICK_GERBER_FILE -> {
//                    if (ext != "gbr") {
//                        if (requestCode == PICK_GERBER_FILE) {
//                            Toast.makeText(
//                                this,
//                                "Hanya file .gbr yang diperbolehkan",
//                                Toast.LENGTH_SHORT
//                            ).show()
//                            return
//                        }
//                    }
//                    tvGerber.text = fileName
//                    gerberText = content
//                    etGerber.setText(content)
//                    Toast.makeText(this, "Gerber loaded", Toast.LENGTH_SHORT).show()
//                }
//
//                PICK_DRILL_FILE -> {
//                    if (ext != "xln") {
//                        if(requestCode == PICK_DRILL_FILE) {
//                            Toast.makeText(
//                                this,
//                                "Hanya file .xln yang diperbolehkan",
//                                Toast.LENGTH_SHORT
//                            ).show()
//                            return
//                        }
//                    }
//                    tvDrill.text = fileName
//                    drillText = content
//                    etDrill.setText(content)
//                    Toast.makeText(this, "Drill loaded", Toast.LENGTH_SHORT).show()
//                }
//            }
//        }
//    }
//
//    private fun readFileFromUri(uri: Uri): String {
//        return contentResolver.openInputStream(uri)?.bufferedReader()?.use {
//            it.readText()
//        } ?: ""
//    }
//
//    private fun getFileName(uri: Uri): String {
//        var result: String? = null
//        if (uri.scheme == "content") {
//            val cursor = contentResolver.query(uri, null, null, null, null)
//            cursor.use {
//                if (it != null && it.moveToFirst()) {
//                    val index = it.getColumnIndex("_display_name")
//                    if (index != -1) {
//                        result = it.getString(index)
//                    }
//                }
//            }
//        }
//        if (result == null) {
//            result = uri.path
//            val cut = result?.lastIndexOf('/')
//            if (cut != null && cut != -1) {
//                result = result?.substring(cut + 1)
//            }
//        }
//        return result ?: "Unknown File"
//    }
//
//    private fun convertToGcode() {
//        try {
//            gerberText = etGerber.text.toString()
//            drillText = etDrill.text.toString()
//
//            if (gerberText.isEmpty()) {
//                Toast.makeText(this, "Please provide Gerber content!", Toast.LENGTH_SHORT).show()
//                return
//            }
//
//            val settings = Settings(
//                penWidth = etPenWidth.text.toString().toFloatOrNull() ?: 0.5f,
//                penUpHeight =  1.0f,
//                penDownHeight =  0.0f,
//                rapidFeed = 100,
//                drawFeed = etFeedRate.text.toString().toIntOrNull() ?: 60
//            )
//
//            val converter = GerberConverter(settings)
//            val result = converter.convert(gerberText, drillText)
//
//            gcodeText = result.gcode
//            tvGcode.text = gcodeText
//
//            // Update stats
//            val statsText = """
//                Statistics:
//                • Trace segments: ${result.stats.traces}
//                • Pads/Vias: ${result.stats.pads} (${result.stats.padsWithHoles} with holes)
//                • Pen-up moves: ${result.stats.penUpMoves}
//                • Travel distance: ${result.stats.travelDistance}mm
//            """.trimIndent()
//            tvStats.text = statsText
//
//            // Update preview
//            previewView.updatePreview(result.traces, result.pads)
//
//            btnSave.isEnabled = true
//            btnShare.isEnabled = true
//
//            scrollView.post {
//                scrollView.fullScroll(View.FOCUS_DOWN)
//            }
//            Toast.makeText(this, "Conversion successful!", Toast.LENGTH_SHORT).show()
//
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
//            e.printStackTrace()
//        }
//    }
//
//    private fun saveGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "No G-code to save!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        try {
//            // Generate filename with timestamp: Gcode-ddMMyyyy_HHmmss.gcode
//            val dateFormat = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.getDefault())
//            val timestamp = dateFormat.format(Date())
//            val filename = "Gcode-$timestamp.gcode"
//
//            // Save to Downloads folder
//            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
//            val file = File(downloadsDir, filename)
//
//            FileOutputStream(file).use {
//                it.write(gcodeText.toByteArray())
//            }
//
//            Toast.makeText(this, "Saved to Downloads/$filename", Toast.LENGTH_LONG).show()
//        } catch (e: Exception) {
//            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
//            e.printStackTrace()
//        }
//    }
//
//    private fun shareGcode() {
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "No G-code to share!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        try {
//            val file = File(cacheDir, "output.gcode")
//            FileOutputStream(file).use {
//                it.write(gcodeText.toByteArray())
//            }
//
//            val uri = FileProvider.getUriForFile(
//                this,
//                "${packageName}.fileprovider",
//                file
//            )
//
//            val shareIntent = Intent(Intent.ACTION_SEND).apply {
//                type = "text/plain"
//                putExtra(Intent.EXTRA_STREAM, uri)
//                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//            }
//
//            startActivity(Intent.createChooser(shareIntent, "Share G-code"))
//        } catch (e: Exception) {
//            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//    private fun printGcode(){
//        if (gcodeText.isEmpty()) {
//            Toast.makeText(this, "No G-code to print!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        if(stateMesin == "Running") {
//            Toast.makeText(this, "Printer is running!", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        val target = if (isConnected) {
//            PrintActivity::class.java
//        } else {
//            PairingActivity::class.java
//        }
//        getSharedPreferences("APP_PREF", MODE_PRIVATE)
//            .edit()
//            .putString("EXTRA_GCODE", gcodeText)
//            .apply()
//        startActivity(Intent(this, target))
//        finish()
//
//    }
//}