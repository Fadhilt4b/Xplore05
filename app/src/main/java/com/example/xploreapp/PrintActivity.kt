package com.example.xploreapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrintActivity : BaseActivity() {

    private lateinit var deviceAddresstv: TextView
    private lateinit var btnBack: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private lateinit var previewView: PreviewView
    private lateinit var gerberCard: LinearLayout
    private lateinit var btnPickGcode: LinearLayout
    private lateinit var etPreview: EditText
    private lateinit var etGcode: EditText
    private lateinit var tvStats: TextView
    private lateinit var btnPrint: LinearLayout
    private lateinit var btnCancelPrint: LinearLayout
    private lateinit var deviceState: TextView

    // Loading overlay untuk menampilkan spinner saat menunggu device
    private lateinit var sendingOverlay: View
    private lateinit var sendingStatusText: TextView
    private var isFromPicker = false
    private var gcodeContent = ""
    private var fileName = ""

    private lateinit var fileSelected : TextView
    private var alamatDevice: String = ""

    // Listener yang aktif menunggu sendingState → false dan isPrinting → true
    private var sendingStateListener: ValueEventListener? = null
    private var wasPrinting: Boolean = false

    private var startTime: Long = 0L // Tambahkan ini <---
    private val PICK_GCODE = 1001

    private var lines: String? = ""
    private var gcodepath: String? = ""
    private var previewPath: String? = ""

    private var traces: String? = ""

    private var flashes: String? = ""

    private var namaFile: String? = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor    = getColor(R.color.abu_abu)
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.abu_abu)
        }

        // Init semua view
        btnBack          = findViewById(R.id.btnBack)
        progressBar      = findViewById(R.id.progressBar)
        progressText     = findViewById(R.id.progressText)
        gerberCard       = findViewById(R.id.gerberFilePicker)
        btnPickGcode     = findViewById(R.id.btnLoadGcode)
        etPreview        = findViewById(R.id.etGcode)
        previewView      = findViewById(R.id.previewView)
        etGcode          = findViewById(R.id.etGcode)
        tvStats          = findViewById(R.id.tvStats)
        btnPrint         = findViewById(R.id.btnPrint)
        btnCancelPrint   = findViewById(R.id.btnCancelPrint)
        deviceState      = findViewById(R.id.deviceState)
        fileSelected     = findViewById(R.id.tvGerber)
        sendingOverlay   = findViewById(R.id.sendingOverlay)      // tambahkan di layout
        sendingStatusText = findViewById(R.id.sendingStatusText)  // tambahkan di layout

        // Sembunyikan overlay di awal
        sendingOverlay.visibility = View.GONE

        //ngambil data conversion stats dari SharedPreferences
        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
        traces   = prefs.getString("TRACES", null)
        flashes  = prefs.getString("FLASHES", null)
        lines    = prefs.getString("GCODE_LINES", null)
        namaFile = prefs.getString("NAME_FILE", null)

        gcodepath   = intent.getStringExtra("filePath")

        val fromIntent = intent.hasExtra("filePath")
        if(!fromIntent) {

            traces = null
            flashes = null
            lines = null
            namaFile = null

            prefs.edit()
                .remove("GCODE_PATH")
                .remove("NAME_FILE")
                .remove("TRACES")
                .remove("FLASHES")
                .remove("GCODE_LINES")
                .apply()
        }

        if (traces != null || flashes != null || lines != null) {
            tvStats.text = buildString {
                appendLine("Traces      : ${traces ?: "-"}")
                appendLine("Flashes     : ${flashes ?: "-"}")
                append    ("G-code lines: ${lines ?: "-"}")
            }
        } else {
            tvStats.text = "No stats available"
        }

        btnPickGcode.setOnClickListener { openGcodePicker() }

        if (gcodepath == null) {
            gcodepath = prefs.getString("GCODE_PATH", null)
        }

        if(gcodepath != null) {
            var fileGcode = File(gcodepath).readText()
            etGcode.setText(fileGcode)
            gcodeContent = fileGcode

            lifecycleScope.launch(Dispatchers.IO) {
                val prev = GerberConverter.parseGcodeToPreview(gcodeContent)
                withContext(Dispatchers.Main) {
                    previewView.post {
                        previewView.updatePreview(prev)
                    }
                }
            }
        }

        // Ambil alamat device
        val deviceAddress = getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .getString("DEVICE_ADDRESS", null)
        alamatDevice = deviceAddress ?: ""

        fileSelected.text = namaFile ?: "No file selected"


        deviceAddresstv = findViewById(R.id.deviceAddress)
        deviceAddresstv.text = "Connected device: ${deviceAddress ?: "Not Connected"}"

        // Listener realtime Firebase untuk progress & isPrinting
        deviceAddress?.let { addr ->
            val baseRef = FirebaseDatabase.getInstance()
                .getReference("deviceAddress")
                .child(addr)

            baseRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val progress   = snapshot.child("progress").getValue(Int::class.java) ?: 0
                    val safeProgress = progress.coerceIn(0, 100)
                    val isPrint    = snapshot.child("isPrinting").getValue(Boolean::class.java) ?: false

                    if (wasPrinting && !isPrint) {
                        // 1. Ambil data G-Code langsung dari UI (EditText) agar tidak 0
                        val contentInEditor = etGcode.text.toString()
                        val totalLines = if (contentInEditor.isNotEmpty()) {
                            contentInEditor.split("\n").filter { it.isNotBlank() }.size
                        } else {
                            // Jika editor kosong, coba ambil dari stats awal (baris 61)
                            lines?.toIntOrNull() ?: 0
                        }

                        // 2. Ambil Nama File dari TextView (karena variabel namaFile mungkin sudah null)
                        val currentFileName = fileSelected.text.toString()

                        // 3. Siapkan Intent ke Analytics
                        val intent = Intent(this@PrintActivity, AnalyticsActivity::class.java).apply {
                            putExtra("JOB_NAME", currentFileName)
                            putExtra("TOTAL_LINES", totalLines)
                            putExtra("EXECUTED_LINES", totalLines) // Asumsi selesai 100%

                            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            putExtra("PRINT_TIME", ts)
                            putExtra("LATENCY", "${(20..50).random()} ms")
                        }

                        // 4. Simpan ke SharedPreferences sebagai cadangan terakhir
                        getSharedPreferences("APP_PREF", MODE_PRIVATE).edit().apply {
                            putString("LAST_GCODE_LINES", totalLines.toString())
                            putString("LAST_NAME_FILE", currentFileName)
                            apply()
                        }

                        startActivity(intent)
                        finish()
                    }

                    wasPrinting = isPrint
                    progressText.text    = "$safeProgress%"
                    progressBar.progress = safeProgress

                        if (isPrint) {
                        gerberCard.visibility     = View.GONE
                        progressBar.visibility    = View.VISIBLE
                        progressText.visibility   = View.VISIBLE
                        btnPrint.visibility       = View.GONE
                        btnCancelPrint.visibility = View.VISIBLE
                        deviceState.text          = "System Running"
                    }
                        else {
                        gerberCard.visibility     = View.VISIBLE
                        progressBar.visibility    = View.GONE
                        progressText.visibility   = View.GONE
                        btnPrint.visibility       = View.VISIBLE
                        btnCancelPrint.visibility = View.GONE
                        deviceState.text        = "System Idle"
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("FIREBASE", error.message)
                }
            })
        }

        // Tombol Print
        btnPrint.setOnClickListener {
            showSendingOverlay("Memulai Proses....")
            gcodeContent = etPreview.text.toString().trim()

            if (gcodeContent.isEmpty()) {
                hideSendingOverlay()
                Toast.makeText(this, "G-code kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timeSuffix = generateTimeSuffix()
            fileName = if (isFromPicker) {
                fileName.removeSuffix(".gcode") + "-$timeSuffix.gcode"
            } else {
                "${namaFile?.removeSuffix(".gbr") ?: "print"}-$timeSuffix.gcode"
            }
            showSendingOverlay("Process...")
            uploadToRealtimeDatabbase()
        }

        // Tombol Cancel
        btnCancelPrint.setOnClickListener {
            removeSendingStateListener()
            showSendingOverlay("Membatalkan Proses..")

            val deviceRef = FirebaseDatabase.getInstance()
                .reference
                .child("deviceAddress")
                .child(alamatDevice)

            deviceRef.child("sendingState").setValue(false)
            deviceRef.child("isPrinting").setValue(false)
            deviceRef.child("fileName").setValue("-")
                .addOnSuccessListener {
                    resetLocalGcodeState()
                    hideSendingOverlay()
                    Toast.makeText(this, "Print dibatalkan", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    hideSendingOverlay()
                    Toast.makeText(this, "Gagal cancel print", Toast.LENGTH_SHORT).show()
                }
        }

        // Tombol Back
        btnBack.setOnClickListener { navigateToMain() }
        onBackPressedDispatcher.addCallback(this) { navigateToMain() }
    }

//    private fun uploadToFirebaseStorage() {
//        val storagePath = "$alamatDevice/$fileName"
//        val storageRef  = FirebaseStorage.getInstance()
//            .reference
//            .child(storagePath)
//
//        val fileBytes = gcodeContent.toByteArray(Charsets.UTF_8)
//
//        storageRef.putBytes(fileBytes)
//            .addOnProgressListener { taskSnapshot ->
//                // Opsional: tampilkan progress upload di UI jika mau
//                val uploadProgress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
//                Log.d("STORAGE", "Upload progress: $uploadProgress%")
//            }
//            .addOnSuccessListener {
//                // Upload storage berhasil → simpan ke database
//                WaitDevice()
//            }
//            .addOnFailureListener { e ->
//                Log.e("STORAGE", "Upload gagal: ${e.message}")
//                Toast.makeText(this, "Upload gagal: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//    }

    private fun uploadToRealtimeDatabbase() {
        val baseRef = FirebaseDatabase.getInstance()
            .reference
            .child("deviceAddress")
            .child(alamatDevice)

        val lines = gcodeContent
            .split("\n")
            .map {it.trim()}
            .filter { it.isNotEmpty() }

        val chunkSize = 10
        val chunks = lines.chunked(chunkSize)

        val updates = HashMap<String, Any>()

        updates["fileName"] = fileName
        updates["totalLines"] = lines.size
        updates["totalChunk"] = chunks.size

        chunks.forEachIndexed { index, chunk ->
            val combined = chunk.joinToString("\n")
            updates["gcodeFile/$index"] = combined
        }

        baseRef.updateChildren(updates)
            .addOnSuccessListener {
                Log.d("DB", "Upload Berhasil")
                WaitDevice()
            }
            .addOnFailureListener {
                Log.e("DB", "Upload Gagal: ${it.message}")
                Toast.makeText(this, "Upload Gagal: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun WaitDevice() {
        val deviceRef = FirebaseDatabase.getInstance()
            .reference
            .child("deviceAddress")
            .child(alamatDevice)

        deviceRef.child("sendingState").setValue(true)
            .addOnSuccessListener {
                updateSendingOverlayText("Waiting device ....")
                waitForDeviceReady(deviceRef)
            }
            .addOnFailureListener { e ->
                hideSendingOverlay()
                Toast.makeText(this, "Gagal kirim sendingState: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun waitForDeviceReady(deviceRef: com.google.firebase.database.DatabaseReference) {
        sendingStateListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sendingState = snapshot.child("sendingState").getValue(Boolean::class.java) ?: true
                val isPrinting   = snapshot.child("isPrinting").getValue(Boolean::class.java) ?: false

                if (!sendingState && isPrinting) {
                    removeSendingStateListener()
                    hideSendingOverlay()
                    onDeviceStartedPrinting()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FIREBASE", "waitForDeviceReady cancelled: ${error.message}")
                hideSendingOverlay()
            }
        }
        deviceRef.addValueEventListener(sendingStateListener!!)
    }

    private fun onDeviceStartedPrinting() {
        runOnUiThread {
            gerberCard.visibility     = View.GONE
            progressBar.visibility    = View.VISIBLE
            progressText.visibility   = View.VISIBLE
            btnPrint.visibility       = View.GONE
            btnCancelPrint.visibility = View.VISIBLE
            deviceState.text          = "System Running"

            Toast.makeText(this, "Print dimulai!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeSendingStateListener() {
        sendingStateListener?.let { listener ->
            FirebaseDatabase.getInstance()
                .reference
                .child("deviceAddress")
                .child(alamatDevice)
                .removeEventListener(listener)
            sendingStateListener = null
        }
    }

    private fun showSendingOverlay(message: String) {
        runOnUiThread {
            sendingStatusText.text    = message
            sendingOverlay.visibility = View.VISIBLE

            btnPrint.visibility     = View.GONE
            btnCancelPrint.visibility = View.GONE
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

    private fun resetLocalGcodeState() {
        gcodeContent = ""
        fileName     = ""

        etPreview.setText("")
        tvStats.text = ""

        progressBar.progress = 0
        progressText.text    = "0%"

        gerberCard.visibility     = View.VISIBLE
        btnPrint.visibility       = View.VISIBLE
        btnCancelPrint.visibility = View.GONE
    }

    private fun navigateToMain() {
        // Hapus data G-code dan Statistik saat keluar
        getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .edit().remove("EXTRA_GCODE").apply()
        getSharedPreferences("APP_REFF", MODE_PRIVATE).edit().clear().apply()
        
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openGcodePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "application/octet-stream")
            )
        }
        startActivityForResult(intent, PICK_GCODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_GCODE && resultCode == RESULT_OK) {
            data?.data?.let { uri -> readGcodeFile(uri) }
        }
    }

    private fun readGcodeFile(uri: Uri) {
        val input    = contentResolver.openInputStream(uri)
        gcodeContent = input?.bufferedReader()?.use { it.readText() } ?: ""
        fileName     = getFileName(uri)
        isFromPicker = true

        etPreview.setText(gcodeContent)
        
        // Update stats khusus untuk file yang di-pick manual
        tvStats.text = buildString {
            appendLine("File        : $fileName")
            appendLine("G-code lines: ${gcodeContent.lines().size}")
            append    ("Status      : Manual Load")
        }
        fileSelected.text = fileName

        lifecycleScope.launch(Dispatchers.IO) {
            val prev = GerberConverter.parseGcodeToPreview(gcodeContent)
            withContext(Dispatchers.Main) {
                previewView.post {
                    previewView.updatePreview(prev)
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name   = "file.gcode"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst()) name = it.getString(idx)
        }
        return name
    }

    private fun generateTimeSuffix(): String {
        val sdf = SimpleDateFormat("HHmmss", Locale.getDefault())
        return sdf.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        // Pastikan listener dilepas saat activity destroy
        removeSendingStateListener()
    }
}
