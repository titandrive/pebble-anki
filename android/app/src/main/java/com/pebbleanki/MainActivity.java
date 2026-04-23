package com.pebbleanki;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start the background service that listens for Pebble messages
        startService(new Intent(this, AnkiPebbleService.class));

        TextView status = findViewById(R.id.status_text);
        status.setText("Service running.\nOpen the Anki app on your Pebble to start.");
    }
}
