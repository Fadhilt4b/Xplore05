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

        window.statusBarColor = getColor(R.color.abu_abu)
        window.navigationBarColor = getColor(R.color.abu_abu)

        btnBack = findViewById(R.id.btnBack)
        btnScan = findViewById(R.id.btnScan)
        address = findViewById(R.id.editTextAddress)
        btnConnect = findViewById(R.id.btnConnect)

        val database = FirebaseDatabase.getInstance()
        val ref = database.getReference("deviceAddress")

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
                                    val intent = Intent(this, PrintActivity::class.java)
                                    startActivity(intent)
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