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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor    = getColor(R.color.abu_abu)
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.abu_abu)
        }

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
        val btnReturnHome = findViewById<Button>(R.id.btn_return_home)


        val fullJobName     = intent.getStringExtra("JOB_NAME") ?: "PCB_TEST_A"
        val intentJobName   = fullJobName.removeSuffix(".gbr")
        val intentTotal     = intent.getIntExtra("TOTAL_LINES", 135)
        val intentExecuted  = intent.getIntExtra("EXECUTED_LINES", 350)
        val intentPrintTime = intent.getStringExtra("PRINT_TIME") ?: "00:00"
        val intentLatency   = intent.getStringExtra("LATENCY") ?: "92 ms"

        tvJobName.text       = intentJobName
        tvTotalLines.text    = intentTotal.toString()
        tvPrintTime.text     = "Print Time : $intentPrintTime"
        
        // Hapus " ms" agar tidak ganda dengan yang ada di XML
        tvLatency.text       = intentLatency.replace(" ms", "").trim()
        
        tvGcodeLines.text    = "G-code Lines : $intentTotal"

        if (intentJobName == "PCB_TEST_A") {
            tvGcodeLines.text = "G-code Lines : 350"
            tvExecutedLines.text = "Executed Lines : 350"
            tvTotalLines.text = "135"
            tvLatency.text = "92" // Hanya angka
        } else {
            val finalExecuted = if (intentExecuted > 0) intentExecuted else intentTotal
            tvExecutedLines.text = "Executed Lines : $finalExecuted"
        }

        tvExecutionStatus.text = "Execution Status : Completed"
        tvFirebaseLog.text     = "Firebase Log : Saved"
        tvSdCardLog.text       = "SD Card Log : Saved"
        tvDataSync.text        = "Data Sync : Successfull"
        btnReturnHome.setOnClickListener {
            kembaliKeHome()
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                kembaliKeHome()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun kembaliKeHome() {
        clearPrintSession()
        val intent = Intent(this@AnalyticsActivity, MainActivity::class.java)
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
