package com.example.xploreapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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

        // Bind Views
        val tvJobName = findViewById<TextView>(R.id.tv_job_name)
        val tvTotalLines = findViewById<TextView>(R.id.tv_total_lines)
        val tvPrintTime = findViewById<TextView>(R.id.tv_print_time)
        val tvLatency = findViewById<TextView>(R.id.tv_latency)
        val tvGcodeLines = findViewById<TextView>(R.id.tv_gcode_lines)
        val tvExecutedLines = findViewById<TextView>(R.id.tv_executed_lines)
        val tvExecutionStatus = findViewById<TextView>(R.id.tv_execution_status)
        val tvFirebaseLog = findViewById<TextView>(R.id.tv_firebase_log)
        val tvSdCardLog = findViewById<TextView>(R.id.tv_sd_card_log)
        val tvDataSync = findViewById<TextView>(R.id.tv_data_sync)
        val tvIotComm = findViewById<TextView>(R.id.tv_iot_communication)
        val tvMachineStatus = findViewById<TextView>(R.id.tv_machine_status)
        val btnReturnHome = findViewById<Button>(R.id.btn_return_home)

        // Get Data from Intent
        jobName = getSharedPreferences("APP_REFF", MODE_PRIVATE)
            .getString("NAME_FILE", null)
        val totalLines = getSharedPreferences("APP_REFF", MODE_PRIVATE)
            .getString("GCODE_LINES", null)
        val printTime = intent.getStringExtra("PRINT_TIME") ?: "00:00"
        val latency = intent.getStringExtra("LATENCY") ?: "0 ms"

        // Set Data
        tvJobName.text = "Job Name : $jobName"
        tvTotalLines.text = "Total Lines : $totalLines"
        tvPrintTime.text = "Print Time : $printTime"
        tvLatency.text = "Latency : $latency"
        tvGcodeLines.text = "G-code Lines : $totalLines"
        tvExecutedLines.text = "Executed Lines : $totalLines"
        tvExecutionStatus.text = "Execution Status : Completed"
        tvFirebaseLog.text = "Firebase Log : Saved"
        tvSdCardLog.text = "SD Card Log : Saved"
        tvDataSync.text = "Data Sync : Successfull"
        tvIotComm.text = "Iot Communication : Stable"
        tvMachineStatus.text = "Machine Status : Normal"

        btnReturnHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }
}
