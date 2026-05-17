package com.example.mycustomapk;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private View iconView;
    private View menuView;
    private WindowManager.LayoutParams iconParams;
    private WindowManager.LayoutParams menuParams;

    private Spinner spnApps;
    private EditText edtManualPackage;
    private EditText edtSearch;
    private EditText edtNewValue;
    private Button btnFirstScan;
    private Button btnNextScan;
    private Button btnWrite;
    private Button btnClose;
    private Button btnIcon;

    private List<String> spinnerItems = new ArrayList<>();
    private List<String> packageNames = new ArrayList<>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Uygulama başlar başlamaz Root Daemon'ı arka planda en yüksek yetkiyle çalıştırıyoruz
        startRootDaemon();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);

        iconView = inflater.inflate(R.layout.overlay_icon, null);
        menuView = inflater.inflate(R.layout.overlay_menu, null);

        int layoutFlag;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        iconParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.x = 150;
        iconParams.y = 250;

        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.CENTER;

        windowManager.addView(iconView, iconParams);
        setupMenuComponents();
        setupTouchMovement();
    }

    private void startRootDaemon() {
        try {
            String appRawPath = getApplicationInfo().nativeLibraryDir + "/../stealth_daemon";
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            // Daemon dosyasını arka planda root olarak asılı bırakıyoruz
            os.writeBytes("chmod 777 " + appRawPath + "\n");
            os.writeBytes(appRawPath + " &\n");
            os.writeBytes("exit\n");
            os.flush();
        } catch (Exception ignored) {}
    }

    private int sendDaemonCommand(int mode, int pid, int value) {
        try {
            LocalSocket socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress("stealth_mem_socket", LocalSocketAddress.Namespace.ABSTRACT);
            socket.connect(address);

            OutputStream os = socket.getOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(mode);
            buf.putInt(pid);
            buf.putInt(value);
            os.write(buf.array());
            os.flush();

            InputStream is = socket.getInputStream();
            byte[] resBuf = new byte[4];
            if (is.read(resBuf) == 4) {
                return ByteBuffer.wrap(resBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            socket.close();
        } catch (Exception e) {
            return -2; // Soket bağlantı hatası daemon çalışmıyor olabilir
        }
        return -1;
    }

    private void setupMenuComponents() {
        spnApps = menuView.findViewById(R.id.spnOverlayApps);
        edtManualPackage = menuView.findViewById(R.id.edtOverlayManualPackage);
        edtSearch = menuView.findViewById(R.id.edtOverlaySearch);
        edtNewValue = menuView.findViewById(R.id.edtOverlayNewValue);
        btnFirstScan = menuView.findViewById(R.id.btnFirstScan);
        btnNextScan = menuView.findViewById(R.id.btnNextScan);
        btnWrite = menuView.findViewById(R.id.btnOverlayWrite);
        btnClose = menuView.findViewById(R.id.btnCloseMenu);
        btnIcon = iconView.findViewById(R.id.btnFloatingIcon);

        loadInstalledApps();

        spnApps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    edtManualPackage.setEnabled(true);
                    edtManualPackage.setVisibility(View.VISIBLE);
                } else {
                    edtManualPackage.setEnabled(false);
                    edtManualPackage.setText("");
                    edtManualPackage.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnIcon.setOnClickListener(v -> {
            windowManager.removeView(iconView);
            windowManager.addView(menuView, menuParams);
        });

        btnClose.setOnClickListener(v -> {
            windowManager.removeView(menuView);
            windowManager.addView(iconView, iconParams);
        });

        btnFirstScan.setOnClickListener(v -> runScanAction(1));
        btnNextScan.setOnClickListener(v -> runScanAction(2));
        btnWrite.setOnClickListener(v -> runScanAction(3));
    }

    private void runScanAction(int mode) {
        try {
            String targetPackage;
            int selectedPosition = spnApps.getSelectedItemPosition();

            if (selectedPosition == 0) {
                targetPackage = edtManualPackage.getText().toString().trim();
                if (targetPackage.isEmpty()) {
                    Toast.makeText(this, "Lütfen manuel bir paket adı girin!", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                targetPackage = packageNames.get(selectedPosition);
            }

            int pid = findPid(targetPackage);
            if (pid <= 0) {
                Toast.makeText(this, "Hedef uygulama aktif çalışmıyor!", Toast.LENGTH_LONG).show();
                return;
            }

            if (mode == 1) {
                String searchStr = edtSearch.getText().toString().trim();
                if (searchStr.isEmpty()) return;
                int val = Integer.parseInt(searchStr);
                int count = sendDaemonCommand(1, pid, val);
                if (count == -2) Toast.makeText(this, "Root sunucusu yanıt vermiyor!", Toast.LENGTH_SHORT).show();
                else Toast.makeText(this, "Tarama bitti. Bulunan: " + count, Toast.LENGTH_SHORT).show();
            } else if (mode == 2) {
                String searchStr = edtSearch.getText().toString().trim();
                if (searchStr.isEmpty()) return;
                int val = Integer.parseInt(searchStr);
                int count = sendDaemonCommand(2, pid, val);
                Toast.makeText(this, "Filtrelendi. Kalan: " + count, Toast.LENGTH_SHORT).show();
            } else if (mode == 3) {
                String writeStr = edtNewValue.getText().toString().trim();
                if (writeStr.isEmpty()) return;
                int val = Integer.parseInt(writeStr);
                int count = sendDaemonCommand(3, pid, val);
                Toast.makeText(this, count + " Adreste değer değiştirildi!", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Lütfen geçerli sayılar girin!", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        spinnerItems.add("[Manuel Paket Adı Gir (.com)]");
        packageNames.add("manual_mode");

        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(app);
            }
        }

        Collections.sort(userApps, (o1, o2) -> o1.loadLabel(pm).toString().compareToIgnoreCase(o2.loadLabel(pm).toString()));

        for (ApplicationInfo app : userApps) {
            spinnerItems.add(app.loadLabel(pm).toString() + " (" + app.packageName + ")");
            packageNames.add(app.packageName);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnApps.setAdapter(adapter);
    }

    private int findPid(String pack) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + pack});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = r.readLine();
            if (l != null && !l.trim().isEmpty()) {
                return Integer.parseInt(l.trim().split(" ")[0]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void setupTouchMovement() {
        iconView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = iconParams.x;
                        initialY = iconParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        iconParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        iconParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(iconView, iconParams);
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (iconView != null) try { windowManager.removeView(iconView); } catch (Exception ignored) {}
        if (menuView != null) try { windowManager.removeView(menuView); } catch (Exception ignored) {}
    }
}
