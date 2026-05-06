package com.example.xploreapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class AnalyticsActivity : AppCompatActivity() {

    private var jobName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)

        // Atur warna status bar
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor    = getColor(R.color.abu_abu)
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.abu_abu)
        }

        // 1. Bind Views (Menghubungkan variabel dengan ID di XML)
        val tvJobName     = findViewById<TextView>(R.id.tv_job_name)
        val tvTotalLines  = findViewById<TextView>(R.id.tv_total_lines)
        val tvPrintTime   = findViewById<TextView>(R.id.tv_print_time)
        val tvLatency     = findViewById<TextView>(R.id.tv_latency)
        val tvGcodeLines  = findViewById<TextView>(R.id.tv_gcode_lines)
        val tvExecutedLines = findViewById<TextView>(R.id.tv_executed_lines)
        val tvExecutionStatus = findViewById<TextView>(R.id.tv_execution_status)
        val tvFirebaseLog = findViewById<TextView>(R.id.tv_firebase_log)
        val tvSdCardLog   = findViewById<TextView>(R.id.tv_sd_card_log)
        val tvDataSync    = findViewById<TextView>(R.id.tv_data_sync)
        val tvIotComm     = findViewById<TextView>(R.id.tv_iot_communication)
        val tvMachineStatus = findViewById<TextView>(R.id.tv_machine_status)
        val btnReturnHome = findViewById<Button>(R.id.btn_return_home)

        // 2. Ambil data yang dikirim dari PrintActivity lewat Intent
        val intentJobName   = intent.getStringExtra("JOB_NAME") ?: "No Name"
        val intentTotal     = intent.getIntExtra("TOTAL_LINES", 0)
        val intentExecuted  = intent.getIntExtra("EXECUTED_LINES", 0)
        val intentPrintTime = intent.getStringExtra("PRINT_TIME") ?: "00:00"
        val intentLatency   = intent.getStringExtra("LATENCY") ?: "0 ms"

        // 3. Tampilkan data ke layar (TextView)
        tvJobName.text       = "Job Name : $intentJobName"
        tvTotalLines.text    = "Total Lines : $intentTotal"
        tvPrintTime.text     = "Print Time : $intentPrintTime"
        tvLatency.text       = "Latency : $intentLatency"
        tvGcodeLines.text    = "G-code Lines : $intentTotal"

        val finalExecuted = if (intentExecuted > 0) intentExecuted else intentTotal
        tvExecutedLines.text = "Executed Lines : $finalExecuted"

        tvExecutionStatus.text = "Execution Status : Completed"
        tvFirebaseLog.text     = "Firebase Log : Saved"
        tvSdCardLog.text       = "SD Card Log : Saved"
        tvDataSync.text        = "Data Sync : Successfull"
        tvIotComm.text         = "Iot Communication : Stable"
        tvMachineStatus.text   = "Machine Status : Normal"

        // 4. Tombol Return Home
        btnReturnHome.setOnClickListener {
            kembaliKeHome()
        }

        // 5. Menangani SWIPE BACK (Agar tidak force close dan balik ke Home)
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                kembaliKeHome()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)

    } // AKHIR dari onCreate

    private fun kembaliKeHome() {
        clearPrintSession()
        val intent = Intent(this@AnalyticsActivity, MainActivity::class.java)
        // Flag ini menghapus semua tumpukan halaman agar kembali ke Home dengan bersih
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun clearPrintSession() {
        PrintSession.previewLines?.clear()
        PrintSession.gcodePath = null
        PrintSession.fileName = ""
        PrintSession.traces = 0
        PrintSession.flashes = 0
        PrintSession.totalLines = 0
    }
}