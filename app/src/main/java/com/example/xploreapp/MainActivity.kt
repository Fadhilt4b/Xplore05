package com.example.xploreapp

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlin.apply
import kotlin.collections.remove

class MainActivity : BaseActivity() {

    private lateinit var btnConvert: LinearLayout
    private lateinit var btnPrint: LinearLayout
    private lateinit var btnDisconnect: LinearLayout
    private lateinit var tvDeviceAddress: TextView
    private lateinit var deviceState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = getColor(R.color.biru_home)
        window.navigationBarColor = getColor(R.color.abu_abu)

        btnConvert = findViewById<LinearLayout>(R.id.btn_convert)
        btnPrint = findViewById<LinearLayout>(R.id.btn_print)
        btnDisconnect = findViewById(R.id.disconnectBt)
        tvDeviceAddress = findViewById(R.id.deviceAddressHome)
        deviceState = findViewById(R.id.deviceState)

        val prefs = getSharedPreferences("APP_PREF", MODE_PRIVATE)
        val deviceAddress = prefs.getString("DEVICE_ADDRESS", null)
        val isConnected = !deviceAddress.isNullOrEmpty()

        if(isConnected) {
            tvDeviceAddress.text = "Connected to " + deviceAddress + " "
            btnDisconnect.visibility = Button.VISIBLE
            deviceState.visibility = View.VISIBLE
        } else {
            tvDeviceAddress.text = "No device connected "
            deviceState.visibility = View.INVISIBLE
            btnDisconnect.visibility = View.INVISIBLE
        }

        btnConvert.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val anim = AnimationUtils.loadAnimation(this, R.anim.click_animation)
                    v.startAnimation(anim)
                }
            }
            false
        }

        btnConvert.setOnClickListener {
            val intent = Intent(this, ConvertActivity::class.java)
            startActivity(intent)
            finish()
        }

        btnPrint.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val anim = AnimationUtils.loadAnimation(this, R.anim.click_animation)
                    v.startAnimation(anim)
                }
            }
            false
        }
        btnPrint.setOnClickListener {
            val target = if (isConnected) {
                PrintActivity::class.java
            } else {
                PairingActivity::class.java
            }
            startActivity(Intent(this, target))
            finish()
        }

        if(isConnected) {
            val baseRef = FirebaseDatabase.getInstance()
                .getReference("deviceAddress")
                .child(deviceAddress ?: "")

            baseRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isPrint = snapshot.child("print").getValue(Boolean::class.java) ?: false

                    if(isPrint) {
                        deviceState.text = "System Running"
                        getSharedPreferences("APP_PREF", MODE_PRIVATE)
                            .edit()
                            .putString("RUNNING STATE", "Running")
                            .apply()
                    } else {
                        deviceState.text = "System Idle"
                        getSharedPreferences("APP_PREF", MODE_PRIVATE)
                            .edit()
                            .putString("RUNNING STATE", "Idle")
                            .apply()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                }
            })

            btnDisconnect.setOnClickListener {
                showLoading()

                baseRef.child("deviceConnected")
                    .setValue(false)
                    .addOnSuccessListener {
                        hideLoading()
                        prefs.edit().remove("DEVICE_ADDRESS").apply()
                        recreate()
                    }
                    .addOnFailureListener {
                        hideLoading()
                        Toast.makeText(this, "Disconnect failed", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }
}
