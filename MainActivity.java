package com.coolerbt.app;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long STATUS_INTERVAL_MS = 5000L;
    private static final long CLOCK_INTERVAL_MS = 60000L;
    private static final int REQUEST_ENABLE_BT = 1001;

    private Button btnConnect, btnPump, btnSpeedSlow, btnSpeedFast, btnMode, btnTimerToggle, btnSetTimer, btnSetClock, btnGetStatus, btnHelp;
    private TextView tvConnectionStatus, tvTemperature, tvHumidity, tvPhoneTime, tvFullStatus;

    private BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothSocket bluetoothSocket;
    private volatile OutputStream outputStream;
    private volatile InputStream inputStream;
    private volatile boolean connected;
    private ReaderThread readerThread;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final Runnable statusRunnable = new Runnable() {
        @Override public void run() {
            if (connected) sendCommand("s");
            handler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };
    private final Runnable clockRunnable = new Runnable() {
        @Override public void run() {
            updatePhoneTime();
            handler.postDelayed(this, CLOCK_INTERVAL_MS);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Boolean value : result.values()) if (!Boolean.TRUE.equals(value)) granted = false;
                if (granted) showPairedDevices();
                else Toast.makeText(this, R.string.permission_bluetooth_required, Toast.LENGTH_LONG).show();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setControlsEnabled(false);
        setupListeners();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        updatePhoneTime();
        handler.post(statusRunnable);
        handler.post(clockRunnable);
    }

    private void bindViews() {
        btnConnect = findViewById(R.id.btnConnect); tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvTemperature = findViewById(R.id.tvTemperature); tvHumidity = findViewById(R.id.tvHumidity); tvPhoneTime = findViewById(R.id.tvPhoneTime);
        btnPump = findViewById(R.id.btnPump); btnSpeedSlow = findViewById(R.id.btnSpeedSlow); btnSpeedFast = findViewById(R.id.btnSpeedFast);
        btnMode = findViewById(R.id.btnMode); btnTimerToggle = findViewById(R.id.btnTimerToggle); btnSetTimer = findViewById(R.id.btnSetTimer);
        btnSetClock = findViewById(R.id.btnSetClock); btnGetStatus = findViewById(R.id.btnGetStatus); tvFullStatus = findViewById(R.id.tvFullStatus); btnHelp = findViewById(R.id.btnHelp);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> { if (connected) disconnect(); else requestBluetoothAccess(); });
        btnPump.setOnClickListener(v -> sendCommand("1"));
        btnSpeedSlow.setOnClickListener(v -> sendCommand("2"));
        btnSpeedFast.setOnClickListener(v -> sendCommand("3"));
        btnMode.setOnClickListener(v -> sendCommand("4"));
        btnTimerToggle.setOnClickListener(v -> sendCommand("T"));
        btnSetTimer.setOnClickListener(v -> showTimerDialog());
        btnSetClock.setOnClickListener(v -> showClockDialog());
        btnGetStatus.setOnClickListener(v -> sendCommand("s"));
        btnHelp.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(R.string.dialog_help_title).setMessage(R.string.help_text).setPositiveButton(R.string.btn_close, null).show());
    }

    private void requestBluetoothAccess() {
        if (bluetoothAdapter == null) { toast(R.string.bluetooth_not_supported); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> permissions = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!permissions.isEmpty()) { permissionLauncher.launch(permissions.toArray(new String[0])); return; }
        }
        if (!bluetoothAdapter.isEnabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT); } catch (SecurityException e) { toast(R.string.permission_bluetooth_required); }
            return;
        }
        showPairedDevices();
    }

    private void showPairedDevices() {
        if (bluetoothAdapter == null) return;
        try {
            if (!bluetoothAdapter.isEnabled()) { requestBluetoothAccess(); return; }
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                new AlertDialog.Builder(this).setTitle(R.string.dialog_select_device_title).setMessage(R.string.no_paired_devices).setPositiveButton(R.string.btn_close, null).show();
                return;
            }
            List<BluetoothDevice> devices = new ArrayList<>(bonded);
            Collections.sort(devices, (a,b) -> safeName(a).compareToIgnoreCase(safeName(b)));
            String[] labels = new String[devices.size()];
            for (int i=0;i<devices.size();i++) labels[i] = safeName(devices.get(i)) + "\n" + devices.get(i).getAddress();
            new AlertDialog.Builder(this).setTitle(R.string.dialog_select_device_title).setItems(labels, (d, which) -> connect(devices.get(which))).setNegativeButton(R.string.btn_cancel, null).show();
        } catch (SecurityException e) { toast(R.string.permission_bluetooth_required); }
    }

    private String safeName(BluetoothDevice device) {
        try { String name = device.getName(); return name == null || name.trim().isEmpty() ? "Bluetooth device" : name; }
        catch (SecurityException e) { return "Bluetooth device"; }
    }

    private void connect(final BluetoothDevice device) {
        setConnecting(true);
        ioExecutor.execute(() -> {
            BluetoothSocket socket = null;
            try {
                if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                bluetoothSocket = socket; inputStream = in; outputStream = out; connected = true;
                readerThread = new ReaderThread(in); readerThread.start();
                final String name = safeName(device);
                runOnUiThread(() -> onConnected(name));
            } catch (IOException | SecurityException e) {
                closeSocket(socket); runOnUiThread(this::onConnectFailed);
            }
        });
    }

    private void setConnecting(boolean value) {
        btnConnect.setEnabled(!value);
        tvConnectionStatus.setText(value ? R.string.status_connecting : (connected ? R.string.status_connected_prefix : R.string.status_disconnected));
    }

    private void onConnected(String name) {
        btnConnect.setEnabled(true); btnConnect.setText(R.string.btn_disconnect); tvConnectionStatus.setText(getString(R.string.status_connected_prefix) + name);
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.colorConnected)); setControlsEnabled(true); sendCommand("s");
    }

    private void onConnectFailed() {
        connected = false; btnConnect.setEnabled(true); btnConnect.setText(R.string.btn_connect); tvConnectionStatus.setText(R.string.status_connect_failed);
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.colorDisconnected)); setControlsEnabled(false); toast(R.string.status_connect_failed);
    }

    private void disconnect() {
        connected = false;
        if (readerThread != null) { readerThread.cancel(); readerThread = null; }
        closeSocket(bluetoothSocket);
        runOnUiThread(() -> { btnConnect.setText(R.string.btn_connect); tvConnectionStatus.setText(R.string.status_disconnected); tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.colorDisconnected)); setControlsEnabled(false); });
    }

    private void connectionLost() {
        if (!connected) return;
        connected = false; if (readerThread != null) readerThread.cancel(); closeSocket(bluetoothSocket);
        runOnUiThread(() -> { btnConnect.setText(R.string.btn_connect); tvConnectionStatus.setText(R.string.status_connection_lost); tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.colorDisconnected)); setControlsEnabled(false); toast(R.string.status_connection_lost); });
    }

    private void closeSocket(BluetoothSocket socket) {
        outputStream = null; inputStream = null; bluetoothSocket = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private void setControlsEnabled(boolean enabled) {
        btnPump.setEnabled(enabled); btnSpeedSlow.setEnabled(enabled); btnSpeedFast.setEnabled(enabled); btnMode.setEnabled(enabled);
        btnTimerToggle.setEnabled(enabled); btnSetTimer.setEnabled(enabled); btnSetClock.setEnabled(enabled); btnGetStatus.setEnabled(enabled);
    }

    private void sendCommand(String command) {
        OutputStream out = outputStream;
        if (!connected || out == null) { if (!isFinishing()) toast(R.string.toast_not_connected); return; }
        ioExecutor.execute(() -> {
            try { out.write(command.getBytes(StandardCharsets.US_ASCII)); out.flush(); }
            catch (IOException | SecurityException e) { connectionLost(); }
        });
    }

    private class ReaderThread extends Thread {
        private final InputStream input; private volatile boolean running = true;
        ReaderThread(InputStream input) { this.input = input; }
        void cancel() { running = false; try { input.close(); } catch (IOException ignored) { } }
        @Override public void run() {
            StringBuilder buffer = new StringBuilder(); byte[] data = new byte[256];
            while (running) {
                try {
                    int count = input.read(data); if (count < 0) break;
                    for (int i=0;i<count;i++) { char c=(char)(data[i]&0xFF); if (c=='\n'||c=='\r') { if(buffer.length()>0){ String line=buffer.toString().trim(); buffer.setLength(0); runOnUiThread(() -> handleLine(line)); } } else if (buffer.length()<4096) buffer.append(c); }
                } catch (IOException e) { if(running) connectionLost(); break; }
            }
        }
    }

    private void handleLine(String line) {
        if (line.isEmpty()) return; tvFullStatus.setText(line);
        String[] parts=line.split(",");
        for(String part:parts){ int colon=part.indexOf(':'); if(colon<0) continue; String key=part.substring(0,colon).trim().toUpperCase(Locale.US); String value=part.substring(colon+1).trim();
            if("T".equals(key)) tvTemperature.setText("دما: "+value+" °C"); else if("H".equals(key)) tvHumidity.setText("رطوبت: "+value+" %");
        }
    }

    private void showTimerDialog() {
        View view= LayoutInflater.from(this).inflate(R.layout.dialog_timer,null); TimePicker on=view.findViewById(R.id.timePickerOn), off=view.findViewById(R.id.timePickerOff); on.setIs24HourView(true); off.setIs24HourView(true);
        new AlertDialog.Builder(this).setTitle(R.string.dialog_set_timer_title).setView(view).setPositiveButton(R.string.btn_save,(d,w)->{ String a="ON"+pickerTime(on), b="OFF"+pickerTime(off); sendCommand(a); handler.postDelayed(()->sendCommand(b),300); toast(R.string.toast_timer_saved); }).setNegativeButton(R.string.btn_cancel,null).show();
    }

    private void showClockDialog() {
        View view=LayoutInflater.from(this).inflate(R.layout.dialog_clock,null); TimePicker picker=view.findViewById(R.id.timePickerClock); picker.setIs24HourView(true); Calendar now=Calendar.getInstance(); setPicker(picker,now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE));
        new AlertDialog.Builder(this).setTitle(R.string.dialog_set_clock_title).setView(view).setPositiveButton(R.string.btn_save,(d,w)->{ sendCommand("S"+pickerTime(picker)); toast(R.string.toast_clock_saved); }).setNegativeButton(R.string.btn_cancel,null).show();
    }

    @SuppressWarnings("deprecation") private String pickerTime(TimePicker p){ return String.format(Locale.US,"%02d%02d",p.getCurrentHour(),p.getCurrentMinute()); }
    @SuppressWarnings("deprecation") private void setPicker(TimePicker p,int h,int m){ p.setCurrentHour(h); p.setCurrentMinute(m); }
    private void updatePhoneTime(){ tvPhoneTime.setText("ساعت گوشی: "+new SimpleDateFormat("HH:mm",Locale.US).format(Calendar.getInstance().getTime())); }
    private void toast(int res){ if(!isFinishing()) Toast.makeText(this,res,Toast.LENGTH_SHORT).show(); }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){ super.onActivityResult(requestCode,resultCode,data); if(requestCode==REQUEST_ENABLE_BT && resultCode==RESULT_OK) showPairedDevices(); }
    @Override protected void onDestroy(){ super.onDestroy(); handler.removeCallbacksAndMessages(null); if(readerThread!=null) readerThread.cancel(); closeSocket(bluetoothSocket); ioExecutor.shutdownNow(); }
}
