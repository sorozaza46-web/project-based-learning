package com.example.mycustomapk;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataOutputStream;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ana ekrana sadece servisi başlatan temiz bir buton koyuyoruz
        Button btnLaunch = new Button(this);
        btnLaunch.setText("KAYAN MENÜYÜ BAŞLAT");
        setContentView(btnLaunch);

        btnLaunch.setOnClickListener(v -> {
            if (checkRoot() && checkOverlayPermission()) {
                Intent intent = new Intent(MainActivity.this, OverlayService.class);
                startService(intent);
                finish(); // Ana ekranı kapatıyoruz ki arka planda gizli kalsın
            }
        });
    }

    private boolean checkRoot() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("exit\n"); os.flush();
            return p.waitFor() == 0;
        } catch (Exception e) {
            Toast.makeText(this, "Root Yetkisi Gerekli!", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean checkOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
                Toast.makeText(this, "Lütfen Ekran Üzerinde Gösterim İznini Verin!", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }
}
