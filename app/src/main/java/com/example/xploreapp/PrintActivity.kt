package com.example.xploreapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.cardview.widget.CardView
import androidx.compose.material3.TopAppBar
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.apply
import kotlin.collections.remove

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
    private var isFromPicker = false

    private var secretCode = ""
    private var gcodeContent = ""
    private var fileName = ""

    private var alamatDevice: String = " "

    private val PICK_GCODE = 1001

    private var uploadCall: Call? = null
    private val UPLOAD_TIMEOUT_MS = 10_000L // 10 detik


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print)

        window.statusBarColor = getColor(R.color.abu_abu)
        window.navigationBarColor = getColor(R.color.abu_abu)

        btnBack = findViewById(R.id.btnBack)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        gerberCard = findViewById(R.id.gerberFilePicker)
        btnPickGcode = findViewById(R.id.btnLoadGcode)
        etPreview = findViewById(R.id.etGcode)
        etGcode = findViewById(R.id.etGcode)
        tvStats = findViewById(R.id.tvStats)
        btnPrint = findViewById(R.id.btnPrint)
        btnCancelPrint = findViewById(R.id.btnCancelPrint)
        deviceState = findViewById(R.id.deviceState)

        val gcode = getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .getString("EXTRA_GCODE", null)
        etGcode.setText(gcode ?: "")

        btnPickGcode.setOnClickListener {
            openGcodePicker()
        }

        val deviceAddress = getSharedPreferences("APP_PREF", MODE_PRIVATE)
            .getString("DEVICE_ADDRESS", null)

        alamatDevice = deviceAddress.toString()

        deviceAddress?.let { deviceAddress ->
            val baseRef = FirebaseDatabase.getInstance()
                .getReference("deviceAddress")
                .child(deviceAddress)

            baseRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    val progress = snapshot.child("progress").getValue(Int::class.java) ?: 0
                    val safeProgress = progress.coerceIn(0, 100)

                    val isPrint = snapshot.child("print").getValue(Boolean::class.java) ?: false

                    // Update Progress
                    progressBar.progress = safeProgress
                    progressText.text = "$safeProgress%"

                    // Update UI State
                    if (isPrint) {
                        gerberCard.visibility = View.GONE
                        progressBar.visibility = View.VISIBLE
                        progressText.visibility = View.VISIBLE
                        btnPrint.visibility = View.GONE
                        btnCancelPrint.visibility = View.VISIBLE
                        deviceState.text = "System Running"
                    } else {
                        progressBar.visibility = View.GONE
                        progressText.visibility = View.GONE
                        deviceState.text = "System Idle"
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("FIREBASE", error.message)
                }
            })

        }

        deviceAddresstv = findViewById(R.id.deviceAddress)
        deviceAddresstv.text = "Connected device: " + deviceAddress

        btnPrint.setOnClickListener {
            showLoading()
            gcodeContent = etPreview.text.toString().trim()

            if (gcodeContent.isEmpty()) {
                hideLoading()
                Toast.makeText(this, "G-code kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timeSuffix = generateTimeSuffix()

            fileName = if (isFromPicker) {
                fileName.removeSuffix(".gcode") + "_$timeSuffix.gcode"
            } else {
                "X_Plore_$timeSuffix.gcode"
            }
            uploadToGithubAndSendFirebase()
        }

        btnCancelPrint.setOnClickListener {
            uploadCall?.cancel()

            showLoading()
            val db = FirebaseDatabase.getInstance().reference
            val deviceRef = db.child("deviceAddress").child(alamatDevice)

            deviceRef.child("linkGcode").setValue("-")
            deviceRef.child("print").setValue(false)
                .addOnSuccessListener {
                    resetLocalGcodeState()
                    hideLoading()
                    Toast.makeText(this, "Print dibatalkan", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    hideLoading()
                    Toast.makeText(this, "Gagal cancel print", Toast.LENGTH_SHORT).show()
                }
        }

        btnBack.setOnClickListener {
            getSharedPreferences("APP_PREF", MODE_PRIVATE)
                .edit().remove("EXTRA_GCODE").apply()
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
        onBackPressedDispatcher.addCallback(this) {
            getSharedPreferences("APP_PREF", MODE_PRIVATE)
                .edit().remove("EXTRA_GCODE").apply()
            val intent = Intent(this@PrintActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }


    private fun resetLocalGcodeState() {
        gcodeContent = ""
        fileName = ""

        etPreview.setText("")
        tvStats.text = ""

        progressBar.progress = 0
        progressText.text = "0%"

        gerberCard.visibility = View.VISIBLE
        btnPrint.visibility = View.VISIBLE
        btnCancelPrint.visibility = View.GONE
    }


    private fun openGcodePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain",
                "application/octet-stream"
            ))
        }
        startActivityForResult(intent, PICK_GCODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                readGcodeFile(uri)
            }
        }
    }

    private fun generateTimeSuffix(): String {
        val sdf = SimpleDateFormat("HHMMyy_HHmmss", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun readGcodeFile(uri: Uri) {
        val input = contentResolver.openInputStream(uri)
        gcodeContent = input?.bufferedReader()?.use { it.readText() } ?: ""

        fileName = getFileName(uri)
        isFromPicker = true

        etPreview.setText(gcodeContent)

        val lineCount = gcodeContent.lines().size
        tvStats.text = "File: $fileName\nJumlah baris: $lineCount"
    }


    private fun getFileName(uri: Uri): String {
        var name = "file.gcode"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst()) {
                name = it.getString(idx)
            }
        }
        return name
    }
    private fun uploadToGithubAndSendFirebase() {
        val githubToken = "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"
        val repo = "xadityacndrp/capstone-gcode-storage"
        val branch = "gcode"
        val path = "fileGcode/$alamatDevice/$fileName"


        val contentBase64 =
            Base64.encodeToString(gcodeContent.toByteArray(), Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("message", "upload $fileName")
            put("content", contentBase64)
            put("branch", branch)
        }

        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            json.toString()
        )


        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/contents/$path")
            .addHeader("Authorization", "token $githubToken")
            .put(body)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        uploadCall = client.newCall(request)

        val handler = android.os.Handler(mainLooper)

        val timeoutRunnable = Runnable {
            if (uploadCall != null && !uploadCall!!.isCanceled()) {
                uploadCall!!.cancel()
                hideLoading()
                Toast.makeText(
                    this,
                    "Upload Timeout",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        handler.postDelayed(timeoutRunnable, UPLOAD_TIMEOUT_MS)

        uploadCall!!.enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                handler.removeCallbacks(timeoutRunnable)

                runOnUiThread {
                    hideLoading()
                    Toast.makeText(this@PrintActivity, "Upload Dibatalkan", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                handler.removeCallbacks(timeoutRunnable)

                if (response.isSuccessful) {
                    val rawUrl = "https://raw.githubusercontent.com/$repo/$branch/$path"
                    sendToFirebase(rawUrl)
                } else {
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this@PrintActivity, "Upload gagal", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        })

//        OkHttpClient().newCall(request).enqueue(object : Callback {
//            override fun onFailure(call: Call, e: IOException) {
//                runOnUiThread {
//                    hideLoading()
//                    Toast.makeText(this@PrintActivity, "Upload gagal", Toast.LENGTH_SHORT).show()
//                }
//            }
//
//            override fun onResponse(call: Call, response: Response) {
//                if (response.isSuccessful) {
//                    val rawUrl =
//                        "https://raw.githubusercontent.com/$repo/$branch/$path"
//
//                    sendToFirebase(rawUrl)
//                }
//            }
//        })
    }

    private fun sendToFirebase(rawUrl: String) {
        val db = FirebaseDatabase.getInstance().reference
        val deviceRef = db.child("deviceAddress").child(alamatDevice)

        deviceRef.child("linkGcode")
            .setValue(rawUrl)
            .addOnSuccessListener {
                hideLoading()
                deviceRef.child("print").setValue(true)
            }

        runOnUiThread {
            hideLoading()
            Toast.makeText(this, "Print dikirim", Toast.LENGTH_SHORT).show()
        }
    }

}