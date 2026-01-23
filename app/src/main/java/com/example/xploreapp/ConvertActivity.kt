package com.example.xploreapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConvertActivity : AppCompatActivity() {

    private lateinit var tvFileName: TextView


    private lateinit var btnBack: ImageView
    private lateinit var btnLoadGerber: LinearLayout
    private lateinit var btnLoadDrill: LinearLayout
    private lateinit var btnConvert: Button
    private lateinit var btnPrint: LinearLayout
    private lateinit var btnSave: LinearLayout
    private lateinit var btnShare: LinearLayout

    private lateinit var etGerber: EditText
    private lateinit var etDrill: EditText
    private lateinit var etPenWidth: EditText
    private lateinit var etFeedRate: EditText
    private lateinit var tvStats: TextView
    private lateinit var tvGcode: TextView
    private lateinit var previewView: PreviewView
    private lateinit var scrollView: ScrollView
    private lateinit var tvGerber: TextView
    private lateinit var tvDrill: TextView



    private var isConnected = false

    private var gerberText = ""
    private var drillText = ""
    private var gcodeText = ""
    private var stateMesin = ""

    private val PICK_GERBER_FILE = 1
    private val PICK_DRILL_FILE = 2

//    private val filePickerLauncher = registerForActivityResult(
//        ActivityResultContracts.StartActivityForResult()
//    ) { result ->
//        if (result.resultCode == Activity.RESULT_OK) {
//            val data: Intent? = result.data
//            val uri: Uri? = data?.data
//            uri?.let {
//                val fileName = getFileName(it)
//                tvFileName.text = fileName
//            }
//        }
//    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_convert)

        window.statusBarColor = getColor(R.color.abu_abu)
        window.navigationBarColor = getColor(R.color.abu_abu)

        tvFileName = findViewById(R.id.tv_file_name)
        btnBack = findViewById(R.id.btn_back)

        btnLoadGerber = findViewById(R.id.btn_choose_file)
        btnLoadDrill = findViewById(R.id.btn_choose_file_Drill)
        btnConvert = findViewById(R.id.btn_convert_process)
        btnPrint = findViewById(R.id.btnPrint)
        btnSave = findViewById(R.id.btn_save)
        btnShare = findViewById(R.id.btnShare)

        etGerber = findViewById(R.id.etGerber)
        etDrill = findViewById(R.id.etDrill)
        etPenWidth = findViewById(R.id.et_pen_width)
        etFeedRate = findViewById(R.id.et_feed_rate)
        tvStats = findViewById(R.id.tvStats)
        tvGcode = findViewById(R.id.tv_gcode_preview)
        previewView = findViewById(R.id.previewView)
        scrollView = findViewById(R.id.scrollView)
        tvGerber = findViewById(R.id.tvGerber)
        tvDrill = findViewById(R.id.tvDrill)

        etFeedRate.setText("1000")
        etPenWidth.setText("0.5")

        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
        val deviceAddress = prefs.getString("DEVICE_ADDRESS", null)

        stateMesin = prefs.getString("RUNNING STATE", "Idle").toString()
        isConnected = !deviceAddress.isNullOrEmpty()

        btnLoadGerber.setOnClickListener {
            openFilePicker(PICK_GERBER_FILE)
        }

        btnLoadDrill.setOnClickListener {
            openFilePicker(PICK_DRILL_FILE)
        }

        btnConvert.setOnClickListener {
            convertToGcode()
        }

        btnPrint.setOnClickListener {
            printGcode()
        }

        btnSave.setOnClickListener {
            saveGcode()
        }

        btnShare.setOnClickListener {
            shareGcode()
        }


        btnBack.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        onBackPressedDispatcher.addCallback(this) {
            val intent = Intent(this@ConvertActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

//    private fun openFilePicker() {
//        val intent = Intent(Intent.ACTION_GET_CONTENT)
//        intent.type = "*/*" // Mengizinkan semua jenis file, bisa diubah ke ".gbr" jika perlu
//        intent.addCategory(Intent.CATEGORY_OPENABLE)
//        filePickerLauncher.launch(intent)
//    }

    private fun openFilePicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!

            val fileName = getFileName(uri)
            val content = readFileFromUri(uri)

            val ext = fileName.substringAfterLast('.', "").lowercase()

            when (requestCode) {
                PICK_GERBER_FILE -> {
                    if (ext != "gbr") {
                        if (requestCode == PICK_GERBER_FILE) {
                            Toast.makeText(
                                this,
                                "Hanya file .gbr yang diperbolehkan",
                                Toast.LENGTH_SHORT
                            ).show()
                            return
                        }
                    }
                    tvGerber.text = fileName
                    gerberText = content
                    etGerber.setText(content)
                    Toast.makeText(this, "Gerber loaded", Toast.LENGTH_SHORT).show()
                }

                PICK_DRILL_FILE -> {
                    if (ext != "xln") {
                        if(requestCode == PICK_DRILL_FILE) {
                            Toast.makeText(
                                this,
                                "Hanya file .xln yang diperbolehkan",
                                Toast.LENGTH_SHORT
                            ).show()
                            return
                        }
                    }
                    tvDrill.text = fileName
                    drillText = content
                    etDrill.setText(content)
                    Toast.makeText(this, "Drill loaded", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun readFileFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.bufferedReader()?.use {
            it.readText()
        } ?: ""
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val index = it.getColumnIndex("_display_name")
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown File"
    }

    private fun convertToGcode() {
        try {
            gerberText = etGerber.text.toString()
            drillText = etDrill.text.toString()

            if (gerberText.isEmpty()) {
                Toast.makeText(this, "Please provide Gerber content!", Toast.LENGTH_SHORT).show()
                return
            }

            val settings = Settings(
                penWidth = etPenWidth.text.toString().toFloatOrNull() ?: 0.5f,
                penUpHeight =  1.0f,
                penDownHeight =  0.0f,
                rapidFeed = 3000,
                drawFeed = etFeedRate.text.toString().toIntOrNull() ?: 1000
            )

            val converter = GerberConverter(settings)
            val result = converter.convert(gerberText, drillText)

            gcodeText = result.gcode
            tvGcode.text = gcodeText

            // Update stats
            val statsText = """
                Statistics:
                • Trace segments: ${result.stats.traces}
                • Pads/Vias: ${result.stats.pads} (${result.stats.padsWithHoles} with holes)
                • Pen-up moves: ${result.stats.penUpMoves}
                • Travel distance: ${result.stats.travelDistance}mm
            """.trimIndent()
            tvStats.text = statsText

            // Update preview
            previewView.updatePreview(result.traces, result.pads)

            btnSave.isEnabled = true
            btnShare.isEnabled = true

            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
            Toast.makeText(this, "Conversion successful!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun saveGcode() {
        if (gcodeText.isEmpty()) {
            Toast.makeText(this, "No G-code to save!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Generate filename with timestamp: Gcode-ddMMyyyy_HHmmss.gcode
            val dateFormat = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val filename = "Gcode-$timestamp.gcode"

            // Save to Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, filename)

            FileOutputStream(file).use {
                it.write(gcodeText.toByteArray())
            }

            Toast.makeText(this, "Saved to Downloads/$filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun shareGcode() {
        if (gcodeText.isEmpty()) {
            Toast.makeText(this, "No G-code to share!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = File(cacheDir, "output.gcode")
            FileOutputStream(file).use {
                it.write(gcodeText.toByteArray())
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share G-code"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun printGcode(){
        if (gcodeText.isEmpty()) {
            Toast.makeText(this, "No G-code to print!", Toast.LENGTH_SHORT).show()
            return
        }

        if(stateMesin == "Running") {
            Toast.makeText(this, "Printer is running!", Toast.LENGTH_SHORT).show()
            return
        }

        val target = if (isConnected) {
            PrintActivity::class.java
        } else {
            PairingActivity::class.java
        }
        getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .edit()
            .putString("EXTRA_GCODE", gcodeText)
            .apply()
        startActivity(Intent(this, target))
        finish()

    }
}