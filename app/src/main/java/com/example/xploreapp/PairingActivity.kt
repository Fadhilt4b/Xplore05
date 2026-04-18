package com.example.xploreapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.firebase.database.FirebaseDatabase
import kotlin.apply

class PairingActivity : BaseActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var btnScan: LinearLayout
    private lateinit var address: EditText
    private lateinit var btnConnect: CardView

    private var lines: String? = ""
    private var gcodepath: String? = ""
    private var traces: String? = ""
    private var flashes: String? = ""
    private var namaFile: String? = ""

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val qrText = result.data?.getStringExtra("QR_RESULT")
                address.setText(qrText)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor    = getColor(R.color.abu_abu)
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.abu_abu)
        }

        btnBack = findViewById(R.id.btnBack)
        btnScan = findViewById(R.id.btnScan)
        address = findViewById(R.id.editTextAddress)
        btnConnect = findViewById(R.id.btnConnect)

        val database = FirebaseDatabase.getInstance()
        val ref = database.getReference("deviceAddress")

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

        btnBack.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        btnScan.setOnClickListener {
            val intent = Intent(this, ScanFuncActivity::class.java)
            scanLauncher.launch(intent)
            startActivity(intent)
        }

        btnConnect.setOnClickListener {
            showLoading()
            val text = address.text.toString()

            if (!text.isEmpty()) {
                ref.child(text).get()
                    .addOnSuccessListener { snapshot ->
                        if (snapshot.exists()) {

                            val deviceConnected = snapshot.child("deviceConnected").getValue(Boolean::class.java)
                            if (deviceConnected == true) {
                                hideLoading()
                                Toast.makeText(
                                    this,
                                    "Device is already connected to another user",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@addOnSuccessListener
                            }

                            getSharedPreferences("APP_PREF", MODE_PRIVATE)
                                .edit()
                                .putString("DEVICE_ADDRESS", text)
                                .apply()

                            FirebaseDatabase.getInstance()
                                .getReference("deviceAddress")
                                .child(text)
                                .child("deviceConnected")
                                .setValue(true)
                                .addOnSuccessListener {
                                    hideLoading()
                                    Log.d("FIREBASE", "deviceConnected value updated to true")
                                    val printIntent = Intent(this, PrintActivity::class.java).apply {
                                        putExtra("filePath", gcodepath)
                                    }

                                    getSharedPreferences("APP_PREF", MODE_PRIVATE).edit()
                                        .putString("GCODE_LINES", lines.toString())
                                        .putString("NAME_FILE",   namaFile)
                                        .putString("TRACES",      traces.toString())
                                        .putString("FLASHES",     flashes.toString())
                                        .putString("GCODE_PATH", gcodepath)
                                        .apply()

                                    startActivity(printIntent)
                                    finish()
                                }
                                .addOnFailureListener {
                                    hideLoading()
                                    Log.e("FIREBASE", "Failed to update deviceConnected value", it)
                                }
                        } else {
                            hideLoading()
                            Toast.makeText(this, "Device Address not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        hideLoading()
                        Log.e("Firebase", "Gagal membaca data", it)
                    }
            } else {
                hideLoading()
                Toast.makeText(this, "Please enter or scan the device address", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            val intent = Intent(this@PairingActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}