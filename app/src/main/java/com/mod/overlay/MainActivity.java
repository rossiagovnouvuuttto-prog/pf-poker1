package com.mod.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Process rootProcess;
    private volatile boolean starting;
    private TextView status;

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        EditText pidInput = new EditText(this);
        pidInput.setHint("Target PID; empty = root helper self-test");
        pidInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(pidInput);

        Button permission = new Button(this);
        permission.setText("Overlay permission");
        permission.setOnClickListener(v -> startActivity(
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        layout.addView(permission);

        Button start = new Button(this);
        start.setText("Start root overlay");
        start.setOnClickListener(v -> {
            if (starting || rootProcess != null) return;
            String input = pidInput.getText().toString().trim();
            final int pid;
            try {
                pid = input.isEmpty() ? 0 : Integer.parseInt(input);
                if (pid < 0) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                status.setText("Invalid PID");
                return;
            }
            starting = true;
            status.setText("Waiting for su approval");
            worker.execute(() -> launch(pid));
        });
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop overlay");
        stop.setOnClickListener(v -> stopOverlay());
        layout.addView(stop);

        status = new TextView(this);
        status.setText("ARM64 root overlay. Offsets are unconfigured.\n" +
            "Displays read diagnostics; no fabricated ESP data.");
        layout.addView(status);
        setContentView(layout);
    }

    private void launch(int pid) {
        try {
            String apk = getApplicationInfo().sourceDir;
            String library = getApplicationInfo().nativeLibraryDir + "/liboverlay.so";
            String command = "CLASSPATH=" + quote(apk) +
                " /system/bin/app_process /system/bin com.mod.overlay.RootOverlay " +
                quote(library) + " " + pid;
            Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
            rootProcess = process;
            starting = false;
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        final String message = line;
                        runOnUiThread(() -> status.setText(message));
                    }
                    int result = process.waitFor();
                    runOnUiThread(() -> status.setText("Root helper exited: " + result));
                } catch (Exception error) {
                    runOnUiThread(() -> status.setText(error.toString()));
                } finally {
                    if (rootProcess == process) rootProcess = null;
                }
            }, "root-output");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception error) {
            rootProcess = null;
            starting = false;
            runOnUiThread(() -> status.setText(error.toString()));
        }
    }

    private void stopOverlay() {
        Process process = rootProcess;
        if (process == null) return;
        try {
            process.getOutputStream().write("quit\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            status.setText("Stopping overlay");
        } catch (Exception error) {
            status.setText(error.toString());
        }
    }

    @Override protected void onDestroy() {
        stopOverlay();
        worker.shutdown();
        super.onDestroy();
    }
}
