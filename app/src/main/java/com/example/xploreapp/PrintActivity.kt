package com.example.xploreapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrintActivity : BaseActivity() {

    private lateinit var deviceAddresstv: TextView
    private lateinit var btnBack: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
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
    private var alamatDevice: String = ""

    // Listener yang aktif menunggu sendingState → false dan isPrinting → true
    private var sendingStateListener: ValueEventListener? = null

    private val PICK_GCODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print)

        window.statusBarColor = getColor(R.color.abu_abu)
        window.navigationBarColor = getColor(R.color.abu_abu)

        // Init semua view
        btnBack          = findViewById(R.id.btnBack)
        progressBar      = findViewById(R.id.progressBar)
        progressText     = findViewById(R.id.progressText)
        gerberCard       = findViewById(R.id.gerberFilePicker)
        btnPickGcode     = findViewById(R.id.btnLoadGcode)
        etPreview        = findViewById(R.id.etGcode)
        etGcode          = findViewById(R.id.etGcode)
        tvStats          = findViewById(R.id.tvStats)
        btnPrint         = findViewById(R.id.btnPrint)
        btnCancelPrint   = findViewById(R.id.btnCancelPrint)
        deviceState      = findViewById(R.id.deviceState)
        sendingOverlay   = findViewById(R.id.sendingOverlay)      // tambahkan di layout
        sendingStatusText = findViewById(R.id.sendingStatusText)  // tambahkan di layout

        // Sembunyikan overlay di awal
        sendingOverlay.visibility = View.GONE

        // Restore gcode dari SharedPreferences
        val gcode = getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .getString("EXTRA_GCODE", null)
        etGcode.setText(gcode ?: "")

        btnPickGcode.setOnClickListener { openGcodePicker() }

        // Ambil alamat device
        val deviceAddress = getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .getString("DEVICE_ADDRESS", null)
        alamatDevice = deviceAddress ?: ""

        val namaFile = getSharedPreferences("APP_REFF", MODE_PRIVATE)
            .getString("FILE_NAME", null)

        deviceAddresstv = findViewById(R.id.deviceAddress)
        deviceAddresstv.text = "Connected device: $deviceAddress"

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

                    progressBar.progress = safeProgress
                    progressText.text    = "$safeProgress%"

                    if (isPrint) {
                        gerberCard.visibility     = View.GONE
                        progressBar.visibility    = View.VISIBLE
                        progressText.visibility   = View.VISIBLE
                        btnPrint.visibility       = View.GONE
                        btnCancelPrint.visibility = View.VISIBLE
                        deviceState.text          = "System Running"
                    } else {
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
                "$namaFile$timeSuffix.gcode"
            }
            uploadToFirebaseStorage()
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

    private fun uploadToFirebaseStorage() {
        val storagePath = "$alamatDevice/$fileName"
        val storageRef  = FirebaseStorage.getInstance()
            .reference
            .child(storagePath)

        val fileBytes = gcodeContent.toByteArray(Charsets.UTF_8)

        storageRef.putBytes(fileBytes)
            .addOnProgressListener { taskSnapshot ->
                // Opsional: tampilkan progress upload di UI jika mau
                val uploadProgress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                Log.d("STORAGE", "Upload progress: $uploadProgress%")
            }
            .addOnSuccessListener {
                // Upload storage berhasil → simpan ke database
                sendFileNameAndWaitDevice()
            }
            .addOnFailureListener { e ->
                Log.e("STORAGE", "Upload gagal: ${e.message}")
                Toast.makeText(this, "Upload gagal: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ─── Kirim fileName + sendingState, lalu tunggu device ──────────────────

    private fun sendFileNameAndWaitDevice() {
        val deviceRef = FirebaseDatabase.getInstance()
            .reference
            .child("deviceAddress")
            .child(alamatDevice)

        // Tampilkan overlay spinner "Menunggu device..."
        showSendingOverlay("Mengirim ke device...")

        deviceRef.child("fileName").setValue(fileName)
            .addOnSuccessListener {
                deviceRef.child("sendingState").setValue(true)
                    .addOnSuccessListener {
                        updateSendingOverlayText("Menunggu device siap...")
                        waitForDeviceReady(deviceRef)
                    }
                    .addOnFailureListener { e ->
                        hideSendingOverlay()
                        Toast.makeText(this, "Gagal kirim sendingState: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { e ->
                hideSendingOverlay()
                Toast.makeText(this, "Gagal kirim fileName: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Pasang listener realtime yang menunggu kondisi:
     *   sendingState == false  DAN  isPrinting == true
     * Ketika terpenuhi → lepas listener dan update UI ke mode "sedang print".
     */
    private fun waitForDeviceReady(deviceRef: com.google.firebase.database.DatabaseReference) {
        sendingStateListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sendingState = snapshot.child("sendingState").getValue(Boolean::class.java) ?: true
                val isPrinting   = snapshot.child("isPrinting").getValue(Boolean::class.java) ?: false

                if (!sendingState && isPrinting) {
                    // Device sudah menerima file dan mulai print
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

    /** Dipanggil setelah device konfirmasi mulai print */
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
            // Sembunyikan tombol agar tidak bisa ditekan saat menunggu
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
        getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .edit().remove("EXTRA_GCODE").apply()
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
        tvStats.text = "File: $fileName\nJumlah baris: ${gcodeContent.lines().size}"
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
        val sdf = SimpleDateFormat("ddMMyy_HHmm", Locale.getDefault())
        return sdf.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        // Pastikan listener dilepas saat activity destroy
        removeSendingStateListener()
    }
}