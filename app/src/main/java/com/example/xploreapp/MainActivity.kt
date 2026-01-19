package com.example.xploreapp

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnConvert = findViewById<LinearLayout>(R.id.btn_convert)
        val btnPrint = findViewById<LinearLayout>(R.id.btn_print)

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
            val intent = Intent(this, PairingActivity::class.java)
            startActivity(intent)
        }
    }
}