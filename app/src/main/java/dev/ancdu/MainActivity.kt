package dev.ancdu

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Stub: Task 10 replaces it.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "ancdu" })
    }
}
