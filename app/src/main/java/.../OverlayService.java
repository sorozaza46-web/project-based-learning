package com.example.mycustomapk;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private View iconView;
    private View menuView;
    private WindowManager.LayoutParams iconParams;
    private WindowManager.LayoutParams menuParams;

    private TextView txtTargetStatus;
    private EditText edtSearch;
    private EditText edtNewValue;
    private Button btnFirstScan;
    private Button btnNextScan;
    private Button btnWrite;
    private Button btnClose;
    private Button btnIcon;
    private Button btnAutoDetect;

    private int detectedPid = -1;
    private String detectedPackageName = "";

    static {
        System.loadLibrary("memory_engine");
    }
    
    public native int firstScan(int pid, int targetValue);
    public native int nextScan(int pid, int targetValue);
    public native int writeNewValue(int pid, int newValue);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. ADIM: SELinux Korumasını Kökten Kapat (process_vm_readv İzin Hatasını Çözer)
        disableSELinux();

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

        // Servis açılır açılmaz ilk otomatik taramayı bir kez tetikle
        autoDetectTargetProcess();
    }

    // Root yetkisiyle SELinux politikasını Permissive moda çeken fonksiyon
    private void disableSELinux() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("setenforce 0\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            p.waitFor();
        } catch (Exception e) {
            Toast.makeText(this, "SELinux kapatılamadı, root yetkisi eksik olabilir!", Toast.LENGTH_LONG).show();
        }
    }

    // 2. ADIM: Otomatik Süreç Bulma Algoritması (GameGuardian Tarzı Otomatik Yakalama)
    private void autoDetectTargetProcess() {
        try {
            // Cihazda o an aktif çalışan tüm süreçlerin adını ve PID değerini alan Linux kabuk komutu
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "ps -A -o PID,NAME"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            
            boolean found = false;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                // Popüler hedef oyun kelimeleri ve senin özel launcher paketlerin
                if (trimmed.contains("minecraft") || 
                    trimmed.contains("sonoyuncu") || 
                    trimmed.contains("com.mojang.") || 
                    trimmed.contains("unity") || 
                    trimmed.contains("tencent") ||
                    trimmed.contains("valve")) {
                    
                    // Satırı boşluklara göre bölüp PID ve paket adını ayırıyoruz
                    String[] tokens = trimmed.split("\\s+");
                    if (tokens.length >= 2) {
                        detectedPid = Integer.parseInt(tokens[0]);
                        detectedPackageName = tokens[1];
                        found = true;
                        break; // İlk eşleşen aktif oyunu al ve çık
                    }
                }
            }
            r.close();
            p.waitFor();

            if (found) {
                txtTargetStatus.setText("Hedef: " + detectedPackageName + " (PID: " + detectedPid + ")");
                txtTargetStatus.setTextColor(0xFF00FF00); // Yeşil renk
            } else {
                txtTargetStatus.setText("Oyun bulunamadı! Lütfen oyunu açın.");
                txtTargetStatus.setTextColor(0xFFFF0000); // Kırmızı renk
                detectedPid = -1;
            }

        } catch (Exception e) {
            txtTargetStatus.setText("Süreç tarama hatası!");
            detectedPid = -1;
        }
    }

    private void setupMenuComponents() {
        // XML arayüzündeki Spinner bileşenini sildiğimiz için durum metni ve otomatik bul butonunu bağlıyoruz
        txtTargetStatus = menuView.findViewById(R.id.txtTargetStatus); // Arayüze ekleyeceğin TextView ID'si
        btnAutoDetect = menuView.findViewById(R.id.btnAutoDetect);     // Arayüze ekleyeceğin Yenile Butonu ID'si
        
        edtSearch = menuView.findViewById(R.id.edtOverlaySearch);
        edtNewValue = menuView.findViewById(R.id.edtOverlayNewValue);
        btnFirstScan = menuView.findViewById(R.id.btnFirstScan);
        btnNextScan = menuView.findViewById(R.id.btnNextScan);
        btnWrite = menuView.findViewById(R.id.btnOverlayWrite);
        btnClose = menuView.findViewById(R.id.btnCloseMenu);
        btnIcon = iconView.findViewById(R.id.btnFloatingIcon);

        btnIcon.setOnClickListener(v -> {
            windowManager.removeView(iconView);
            windowManager.addView(menuView, menuParams);
            // Menü her açıldığında hedefi arka planda otomatik olarak tazele
            autoDetectTargetProcess();
        });

        btnAutoDetect.setOnClickListener(v -> {
            autoDetectTargetProcess();
            if (detectedPid != -1) {
                Toast.makeText(this, "Hedef güncellendi: " + detectedPackageName, Toast.LENGTH_SHORT).show();
            }
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
        if (detectedPid <= 0) {
            Toast.makeText(this, "Aktif bir hedef süreç seçilmedi! Önce otomatik tara yapın.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (mode == 1) {
                String searchStr = edtSearch.getText().toString().trim();
                if (searchStr.isEmpty()) return;
                int val = Integer.parseInt(searchStr);
                
                // JNI katmanından process_vm_readv tetiklenir
                int count = firstScan(detectedPid, val);
                Toast.makeText(this, "Tarama bitti. Bulunan: " + count, Toast.LENGTH_SHORT).show();
            } else if (mode == 2) {
                String searchStr = edtSearch.getText().toString().trim();
                if (searchStr.isEmpty()) return;
                int val = Integer.parseInt(searchStr);
                
                int count = nextScan(detectedPid, val);
                Toast.makeText(this, "Filtrelendi. Kalan: " + count, Toast.LENGTH_SHORT).show();
            } else if (mode == 3) {
                String writeStr = edtNewValue.getText().toString().trim();
                if (writeStr.isEmpty()) return;
                int val = Integer.parseInt(writeStr);
                
                // JNI katmanından process_vm_writev tetiklenir
                int count = writeNewValue(detectedPid, val);
                Toast.makeText(this, count + " Adreste değer başarıyla güncellendi!", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Lütfen geçerli tam sayılar girin!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
