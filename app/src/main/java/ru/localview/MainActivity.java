package ru.localview;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root, list;
    private EditText nameInput, hostInput, portInput, tokenInput;
    private ImageView preview;
    private TextView status;
    private JSONArray pcs = new JSONArray();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("localview", MODE_PRIVATE);
        try { pcs = new JSONArray(prefs.getString("pcs", "[]")); } catch(Exception ignored) {}
        buildUi();
        renderPcs();
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setPadding(0, 8, 0, 8); return t;
    }
    private EditText edit(String hint) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint);
        e.setTextSize(16); return e;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); return b;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 24); scroll.addView(root); setContentView(scroll);
        root.addView(text("LocalView", 26));
        root.addView(text("Удалённый просмотр экранов компьютеров в локальной сети", 14));
        root.addView(text("Добавить компьютер", 19));
        nameInput = edit("Название, например ПК-1"); root.addView(nameInput);
        hostInput = edit("IP-адрес компьютера, например 192.168.1.10"); root.addView(hostInput);
        portInput = edit("Порт (по умолчанию 8765)"); portInput.setText("8765"); root.addView(portInput);
        tokenInput = edit("Токен доступа"); root.addView(tokenInput);
        root.addView(button("Добавить компьютер", this::addPc));
        root.addView(text("Мои компьютеры", 19));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
        status = text("Готово", 14); root.addView(status);
        preview = new ImageView(this); preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview);
    }
    private void addPc() {
        String name = nameInput.getText().toString().trim();
        String host = hostInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if(name.isEmpty() || host.isEmpty() || token.isEmpty()) { status.setText("Заполните название, IP и токен"); return; }
        try {
            int p = Integer.parseInt(port.isEmpty() ? "8765" : port);
            if(p < 1 || p > 65535) throw new Exception();
            JSONObject pc = new JSONObject(); pc.put("name", name); pc.put("host", host); pc.put("port", p); pc.put("token", token);
            pcs.put(pc); save(); renderPcs();
            nameInput.setText(""); hostInput.setText(""); tokenInput.setText("");
            status.setText("Компьютер добавлен");
        } catch(Exception e) { status.setText("Проверьте порт (1–65535)"); }
    }
    private void save() { prefs.edit().putString("pcs", pcs.toString()).apply(); }
    private void renderPcs() {
        list.removeAllViews();
        for(int i=0;i<pcs.length();i++) {
            final int index=i;
            try {
                JSONObject pc=pcs.getJSONObject(i);
                LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0,10,0,10);
                row.addView(text(pc.optString("name")+" — "+pc.optString("host")+":"+pc.optInt("port",8765),16));
                LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
                Button view=button("Получить экран",()->requestScreenshot(index));
                Button del=button("Удалить",()->{ JSONArray next=new JSONArray(); for(int j=0;j<pcs.length();j++) if(j!=index) next.put(pcs.opt(j)); pcs=next; save(); renderPcs(); });
                actions.addView(view,new LinearLayout.LayoutParams(0,-2,1)); actions.addView(del);
                row.addView(actions); list.addView(row);
                View line=new View(this); line.setBackgroundColor(0xFFDDDDDD); list.addView(line,new LinearLayout.LayoutParams(-1,1));
            } catch(Exception ignored) {}
        }
    }
    private void requestScreenshot(int index) {
        try {
            JSONObject pc=pcs.getJSONObject(index);
            String host=pc.getString("host"); int port=pc.optInt("port",8765); String token=pc.getString("token");
            status.setText("Запрашиваю экран: "+pc.optString("name")+"…");
            io.execute(()->{
                HttpURLConnection c=null;
                try {
                    URL url=new URL("http://"+host+":"+port+"/screenshot");
                    c=(HttpURLConnection)url.openConnection(); c.setConnectTimeout(7000); c.setReadTimeout(15000);
                    c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("Accept","image/jpeg");
                    int code=c.getResponseCode();
                    if(code!=200) throw new IOException("Ответ агента: HTTP "+code);
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    try(InputStream in=c.getInputStream()){ byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n); }
                    byte[] bytes=out.toByteArray(); Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                    if(bmp==null) throw new IOException("Не удалось открыть изображение");
                    String when=DateFormat.getDateTimeInstance().format(new Date());
                    main.post(()->{ preview.setImageBitmap(bmp); status.setText("Получен экран "+pc.optString("name")+" · "+when); });
                } catch(Exception e) { String msg=e.getMessage(); main.post(()->status.setText("Ошибка: "+msg)); }
                finally { if(c!=null)c.disconnect(); }
            });
        } catch(Exception e) { status.setText("Не удалось прочитать настройки ПК"); }
    }
    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
