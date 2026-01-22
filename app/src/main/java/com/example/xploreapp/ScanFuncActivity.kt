package com.example.xploreapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator

class ScanFuncActivity: AppCompatActivity() {
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startScan()
            } else {
                Toast.makeText(this, "Camera permission ditolak", Toast.LENGTH_SHORT).show()
                finish()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraPermission.launch(Manifest.permission.CAMERA)

        onBackPressedDispatcher.addCallback(this) {
            val intent = Intent(this@ScanFuncActivity, PairingActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)

        if (result != null && result.contents != null) {
            val intent = Intent()
            intent.putExtra("QR_RESULT", result.contents)

            setResult(Activity.RESULT_OK, intent)
            finish()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan QR Code")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }
}
