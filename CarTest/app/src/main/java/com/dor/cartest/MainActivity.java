package com.dor.cartest;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Test screen for the car unit.
 * Left side: device info + buttons. Right side: connection state, last notification, and a log.
 * The whole UI is built in code (no XML layouts) to keep the project small.
 */
public class MainActivity extends Activity implements AncsClient.Listener, TextToSpeech.OnInitListener {

    private static final int BG = Color.parseColor("#0F1418");
    private static final int CARD = Color.parseColor("#1C252E");
    private static final int TEXT = Color.parseColor("#E8EEF2");
    private static final int MUTED = Color.parseColor("#8A9AA8");
    private static final int ACCENT = Color.parseColor("#2E9CFF");

    private TextView infoText, stateText, notifText, logText;
    private ScrollView logScroll;

    private AncsClient ancs;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String ttsStatus = "בודק...";
    private boolean speakNotifications = true;

    private final StringBuilder log = new StringBuilder();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());

        ancs = new AncsClient(this, this);
        tts = new TextToSpeech(this, this);
        refreshInfo();
        onLog("האפליקציה עלתה. לחץ על 'התחל חיבור לאייפון'");
    }

    @Override
    protected void onDestroy() {
        if (ancs != null) ancs.stop();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // ---- Left column: info + buttons
        ScrollView leftScroll = new ScrollView(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(left);

        TextView title = text("בדיקת מערכת לרכב", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);

        infoText = text("", 15, TEXT);
        infoText.setLineSpacing(0, 1.25f);
        infoText.setBackground(card());
        infoText.setPadding(dp(12), dp(10), dp(12), dp(10));
        left.addView(infoText, margins(0, 8, 0, 8));

        left.addView(button("התחל חיבור לאייפון", v -> ancs.start()), margins(0, 4, 0, 4));
        left.addView(button("עצור חיבור", v -> ancs.stop()), margins(0, 4, 0, 4));
        left.addView(button("בדיקת הקראה בקול", v -> speak("שלום דור, זו בדיקה של הקראה בקול")), margins(0, 4, 0, 4));
        Button speakToggle = button("הקראת התראות: פועל", null);
        speakToggle.setOnClickListener(v -> {
            speakNotifications = !speakNotifications;
            speakToggle.setText(speakNotifications ? "הקראת התראות: פועל" : "הקראת התראות: כבוי");
        });
        left.addView(speakToggle, margins(0, 4, 0, 4));
        left.addView(button("רענן נתונים", v -> refreshInfo()), margins(0, 4, 0, 4));

        // ---- Right column: state + last notification + log
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        stateText = text("לא מחובר", 19, Color.parseColor("#FFD24D"));
        stateText.setTypeface(Typeface.DEFAULT_BOLD);
        right.addView(stateText);

        notifText = text("התראה אחרונה תופיע כאן", 20, TEXT);
        notifText.setBackground(card());
        notifText.setPadding(dp(14), dp(12), dp(14), dp(12));
        notifText.setMinHeight(dp(90));
        right.addView(notifText, margins(0, 8, 0, 8));

        right.addView(text("יומן", 14, MUTED));
        logScroll = new ScrollView(this);
        logText = text("", 13, TEXT);
        logText.setBackground(card());
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(logText);
        right.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        root.addView(leftScroll, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f);
        rp.setMarginStart(dp(16));
        root.addView(right, rp);
        return root;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setMinHeight(dp(56));
        if (onClick != null) b.setOnClickListener(onClick);
        return b;
    }

    private GradientDrawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(10));
        return g;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ device info

    private void refreshInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("דגם: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');
        sb.append("אנדרואיד: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        sb.append("זיכרון RAM: ").append(mb(mi.totalMem)).append(" (פנוי ").append(mb(mi.availMem)).append(")\n");
        sb.append("זיכרון לאפליקציה: ").append(am.getMemoryClass()).append(" MB\n");

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        sb.append("מסך: ").append(dm.widthPixels).append(" x ").append(dm.heightPixels)
                .append(", ").append(dm.densityDpi).append(" dpi\n");
        sb.append("מעבד: ").append(Build.HARDWARE).append(", ליבות: ")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) {
            sb.append("בלוטות': לא נמצא\n");
        } else {
            boolean le = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
            sb.append("בלוטות' LE: ").append(yesNo(le)).append('\n');
            sb.append("בלוטות' דלוק: ").append(yesNo(a.isEnabled())).append('\n');
            if (a.isEnabled()) {
                boolean adv = a.isMultipleAdvertisementSupported() && a.getBluetoothLeAdvertiser() != null;
                sb.append("פרסום BLE (חובה לאייפון): ").append(adv ? "✓ נתמך" : "✗ לא נתמך").append('\n');
            } else {
                sb.append("פרסום BLE: הדלק בלוטות' ולחץ 'רענן נתונים'\n");
            }
        }
        sb.append("הקראה בקול: ").append(ttsStatus);
        infoText.setText(sb.toString());
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    private static String yesNo(boolean b) {
        return b ? "✓ כן" : "✗ לא";
    }

    // ------------------------------------------------------------------ text to speech

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            ttsStatus = "✗ אין מנוע הקראה במכשיר";
        } else {
            Locale[] hebrew = {new Locale("iw", "IL"), new Locale("he", "IL"), new Locale("iw")};
            Locale found = null;
            for (Locale l : hebrew) {
                try {
                    if (tts.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) {
                        found = l;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (found != null) {
                tts.setLanguage(found);
                ttsStatus = "✓ עברית נתמכת";
            } else {
                ttsStatus = "⚠ אין קול בעברית (רק " + Locale.getDefault().getDisplayLanguage() + "/אנגלית)";
                tts.setLanguage(Locale.US);
            }
            ttsReady = true;
        }
        runOnUiThread(this::refreshInfo);
    }

    private void speak(String s) {
        if (!ttsReady) {
            onLog("מנוע ההקראה לא מוכן");
            return;
        }
        tts.speak(s, TextToSpeech.QUEUE_ADD, null, "msg" + System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ AncsClient.Listener

    @Override
    public void onLog(String msg) {
        runOnUiThread(() -> {
            log.append(timeFmt.format(new Date())).append("  ").append(msg).append('\n');
            if (log.length() > 12000) log.delete(0, log.length() - 10000);
            logText.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onState(String state) {
        runOnUiThread(() -> stateText.setText(state));
    }

    @Override
    public void onNotification(AncsClient.Notif n) {
        runOnUiThread(() -> {
            String head = n.appName() + " · " + n.categoryName();
            notifText.setText(head + "\n" + n.title + "\n" + n.message);
            if (speakNotifications && !n.preExisting) {
                String who = n.title.isEmpty() ? n.appName() : n.title;
                speak("הודעה מ" + who + ". " + n.message);
            }
        });
    }
}
