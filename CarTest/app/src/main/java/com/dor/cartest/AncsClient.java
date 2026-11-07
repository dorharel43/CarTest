package com.dor.cartest;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Connects to an iPhone over Bluetooth Low Energy and reads its notifications
 * using Apple's ANCS (Apple Notification Center Service) - the same way smartwatches do it.
 *
 * Flow:
 * 1. We advertise ourselves as a BLE device (so the iPhone can find us).
 * 2. The iPhone connects to us.
 * 3. We open a second "client" connection back to the iPhone and look for ANCS.
 * 4. After pairing, we subscribe to ANCS and the iPhone sends us every notification.
 *
 * All logic runs on the main thread (handler h), so we never have two threads touching the same data.
 */
public class AncsClient {

    public interface Listener {
        void onLog(String msg);
        void onState(String state);
        void onNotification(Notif n);
    }

    /** One notification received from the iPhone. */
    public static class Notif {
        public int uid;
        public int category;
        public boolean preExisting;
        public String appId = "";
        public String title = "";
        public String message = "";

        public String appName() {
            switch (appId) {
                case "net.whatsapp.WhatsApp": return "WhatsApp";
                case "net.whatsapp.WhatsAppSMB": return "WhatsApp Business";
                case "com.apple.MobileSMS": return "הודעות";
                case "com.apple.mobilephone": return "טלפון";
                default: return appId.isEmpty() ? "?" : appId;
            }
        }

        public String categoryName() {
            String[] names = {"אחר", "שיחה נכנסת", "שיחה שלא נענתה", "תא קולי", "רשת חברתית",
                    "לוח זמנים", "מייל", "חדשות", "בריאות", "כספים", "מיקום", "בידור"};
            return category < names.length ? names[category] : String.valueOf(category);
        }
    }

    // Apple's official ANCS identifiers (from Apple's ANCS specification)
    static final UUID ANCS_SERVICE = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    static final UUID NOTIFICATION_SOURCE = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    static final UUID CONTROL_POINT = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    static final UUID DATA_SOURCE = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Our own small service, just so the iPhone has something to connect to
    static final UUID MY_SERVICE = UUID.fromString("c0de1234-0000-4a6b-9a5e-6f1d2c3b4a10");
    static final UUID MY_CHAR = UUID.fromString("c0de1235-0000-4a6b-9a5e-6f1d2c3b4a10");

    private static final int TRANSPORT_LE = 2;
    private static final int MAX_PRE_EXISTING = 3;

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothManager bm;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothGatt gatt;
    private BluetoothDevice phone;
    private BluetoothGattCharacteristic cpChar;

    private boolean running;
    private boolean triedWithoutName;
    private boolean receiverRegistered;
    private boolean advertising;
    private int connectRetries;
    private int preExistingFetched;

    private final ArrayDeque<Notif> pending = new ArrayDeque<>();
    private Notif inFlight;
    private final ByteArrayOutputStream dsBuf = new ByteArrayOutputStream();

    public AncsClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ start / stop

    public void start() {
        if (running) {
            log("כבר פועל");
            return;
        }
        bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            fail("אין בלוטות' במכשיר");
            return;
        }
        if (!adapter.isEnabled()) {
            fail("הבלוטות' כבוי. הדלק אותו ונסה שוב");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported()) {
            fail("המכשיר לא תומך בפרסום BLE (BLE Advertising). בלי זה האייפון לא יכול להתחבר אליו");
            return;
        }

        running = true;
        triedWithoutName = false;
        preExistingFetched = 0;
        registerBondReceiver();

        server = bm.openGattServer(ctx, serverCb);
        if (server == null) {
            stop();
            fail("לא הצלחתי לפתוח GATT Server");
            return;
        }
        BluetoothGattService s = new BluetoothGattService(MY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(new BluetoothGattCharacteristic(MY_CHAR,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
        state("מכין שירות BLE...");
        if (!server.addService(s)) {
            stop();
            fail("הוספת השירות נכשלה");
        }
        // Advertising starts in onServiceAdded
    }

    public void stop() {
        boolean wasRunning = running;
        running = false;
        h.removeCallbacksAndMessages(null);
        stopAdvertising();
        closeClient();
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(bondReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        phone = null;
        pending.clear();
        inFlight = null;
        dsBuf.reset();
        if (wasRunning) state("עצור");
    }

    // ------------------------------------------------------------------ advertising

    private void startAdvertising(boolean withName) {
        if (!running || advertiser == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(MY_SERVICE))
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(withName)
                .build();
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advCb);
        } catch (Exception e) {
            fail("הפרסום נכשל: " + e);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertising) {
            try {
                advertiser.stopAdvertising(advCb);
            } catch (Exception ignored) {
            }
        }
        advertising = false;
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            h.post(() -> {
                advertising = true;
                String name = adapter != null ? adapter.getName() : "?";
                state("מחכה לאייפון...");
                log("✓ הפרסום הצליח! המכשיר גלוי בבלוטות' LE");
                log("שם המכשיר: " + name + (triedWithoutName ? " (השם לא נכלל - ארוך מדי)" : ""));
                log("באייפון: פתח את nRF Connect, חפש את המכשיר ולחץ Connect");
            });
        }

        @Override
        public void onStartFailure(int errorCode) {
            h.post(() -> {
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !triedWithoutName) {
                    triedWithoutName = true;
                    log("שם המכשיר ארוך מדי, מנסה שוב בלי שם");
                    startAdvertising(false);
                    return;
                }
                String reason;
                switch (errorCode) {
                    case ADVERTISE_FAILED_FEATURE_UNSUPPORTED: reason = "החומרה לא תומכת"; break;
                    case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: reason = "יותר מדי מפרסמים פעילים"; break;
                    case ADVERTISE_FAILED_ALREADY_STARTED: reason = "כבר פועל"; break;
                    case ADVERTISE_FAILED_INTERNAL_ERROR: reason = "שגיאה פנימית"; break;
                    default: reason = "קוד " + errorCode;
                }
                fail("הפרסום נכשל: " + reason);
            });
        }
    };

    // ------------------------------------------------------------------ server side (iPhone connects to us)

    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            h.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising(true);
                else fail("הוספת השירות נכשלה, סטטוס " + status);
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            h.post(() -> {
                if (!running) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("📱 מכשיר התחבר: " + device.getAddress());
                    if (phone == null || gatt == null) {
                        phone = device;
                        connectRetries = 0;
                        h.postDelayed(AncsClient.this::connectClient, 800);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("המכשיר התנתק: " + device.getAddress());
                    if (device.equals(phone)) {
                        closeClient();
                        phone = null;
                        state("מחכה לאייפון...");
                    }
                }
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            byte[] full = "CarTest".getBytes(StandardCharsets.UTF_8);
            byte[] part = new byte[0];
            if (offset < full.length) {
                part = new byte[full.length - offset];
                System.arraycopy(full, offset, part, 0, part.length);
            }
            BluetoothGattServer s = server;
            if (s != null) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part);
        }
    };

    // ------------------------------------------------------------------ client side (we read ANCS from the iPhone)

    private void connectClient() {
        if (!running || phone == null) return;
        state("מתחבר לאייפון...");
        closeClient();
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = phone.connectGatt(ctx, false, clientCb, TRANSPORT_LE);
        } else {
            gatt = phone.connectGatt(ctx, false, clientCb);
        }
        if (gatt == null) log("connectGatt החזיר null");
    }

    private void closeClient() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
        }
        gatt = null;
        cpChar = null;
        inFlight = null;
        pending.clear();
        dsBuf.reset();
    }

    private final BluetoothGattCallback clientCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            h.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("✓ חיבור לקוח לאייפון נפתח");
                    h.postDelayed(() -> discover(g), 600);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("חיבור הלקוח נסגר (סטטוס " + status + ")");
                    closeClient();
                    if (running && phone != null && connectRetries++ < 3) {
                        log("מנסה להתחבר שוב (" + connectRetries + "/3)");
                        h.postDelayed(AncsClient.this::connectClient, 1500);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> handleServices(g, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(() -> handleDescriptorWrite(g, d, status));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(() -> {
                if (g != gatt) return;
                if (CONTROL_POINT.equals(c.getUuid()) && status != BluetoothGatt.GATT_SUCCESS) {
                    log("האייפון דחה בקשה (שגיאה 0x" + Integer.toHexString(status) + ")");
                    finishInFlight();
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // Copy right away - the value can change before the handler runs
            byte[] raw = c.getValue();
            final byte[] v = raw == null ? new byte[0] : raw.clone();
            final UUID u = c.getUuid();
            h.post(() -> {
                if (g != gatt) return;
                if (NOTIFICATION_SOURCE.equals(u)) handleNotificationSource(v);
                else if (DATA_SOURCE.equals(u)) handleDataSource(v);
            });
        }
    };

    private void discover(BluetoothGatt g) {
        if (g != gatt) return;
        refreshCache(g);
        if (!g.discoverServices()) log("discoverServices נכשל מיידית");
    }

    private void handleServices(BluetoothGatt g, int status) {
        if (g != gatt) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("גילוי השירותים נכשל (סטטוס " + status + ")");
            return;
        }
        log("נמצאו " + g.getServices().size() + " שירותים באייפון");
        BluetoothGattService ancs = g.getService(ANCS_SERVICE);
        if (ancs == null) {
            if (phone != null && phone.getBondState() != BluetoothDevice.BOND_BONDED) {
                log("שירות ההתראות מוסתר עד שמצמדים. מבקש צימוד...");
                state("ממתין לצימוד: אשר באייפון ובמולטימדיה");
                bond();
            } else {
                log("✗ שירות ההתראות (ANCS) לא נמצא. בדוק שהמכשיר המחובר הוא אייפון");
            }
            return;
        }
        log("✓ נמצא שירות ההתראות של אפל (ANCS)");
        BluetoothGattCharacteristic ns = ancs.getCharacteristic(NOTIFICATION_SOURCE);
        BluetoothGattCharacteristic ds = ancs.getCharacteristic(DATA_SOURCE);
        BluetoothGattCharacteristic cp = ancs.getCharacteristic(CONTROL_POINT);
        if (ns == null || ds == null || cp == null) {
            log("✗ חסרים חלקים בשירות ההתראות");
            return;
        }
        cpChar = cp;
        state("נרשם להתראות...");
        // Apple recommends: subscribe to Data Source first, then Notification Source
        subscribe(ds);
    }

    private void subscribe(BluetoothGattCharacteristic ch) {
        if (gatt == null) return;
        gatt.setCharacteristicNotification(ch, true);
        BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
        if (d == null) {
            log("✗ אין אפשרות להירשם למאפיין " + ch.getUuid());
            return;
        }
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(d)) log("כתיבת ההרשמה נכשלה מיידית");
    }

    private void handleDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
        if (g != gatt) return;
        UUID cu = d.getCharacteristic().getUuid();
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("ההרשמה נכשלה (סטטוס " + status + ")");
            boolean authError = status == 5 || status == 8 || status == 15 || status == 137;
            if (authError && phone != null && phone.getBondState() != BluetoothDevice.BOND_BONDED) {
                log("צריך צימוד. מבקש...");
                state("ממתין לצימוד: אשר באייפון ובמולטימדיה");
                bond();
            }
            return;
        }
        if (DATA_SOURCE.equals(cu)) {
            BluetoothGattService ancs = g.getService(ANCS_SERVICE);
            if (ancs != null) subscribe(ancs.getCharacteristic(NOTIFICATION_SOURCE));
        } else if (NOTIFICATION_SOURCE.equals(cu)) {
            state("✓ מחובר! מחכה להתראות");
            log("✓✓ ההרשמה הצליחה! שלח לעצמך הודעה בוואטסאפ כדי לבדוק");
        }
    }

    // ------------------------------------------------------------------ ANCS data parsing

    /** Notification Source: 8 bytes telling us "a notification was added/changed/removed". */
    private void handleNotificationSource(byte[] v) {
        if (v.length < 8) return;
        int event = v[0] & 0xff;
        int flags = v[1] & 0xff;
        int category = v[2] & 0xff;
        int uid = (v[4] & 0xff) | ((v[5] & 0xff) << 8) | ((v[6] & 0xff) << 16) | ((v[7] & 0xff) << 24);
        boolean pre = (flags & 0x04) != 0;

        if (event != 0) return; // 0 = added, 1 = modified, 2 = removed. We only care about new ones.
        if (pre) {
            // Notifications that were already on the iPhone. Fetch only a few, as a test.
            if (preExistingFetched >= MAX_PRE_EXISTING) return;
            preExistingFetched++;
        }
        Notif n = new Notif();
        n.uid = uid;
        n.category = category;
        n.preExisting = pre;
        pending.add(n);
        pumpRequests();
    }

    /** Ask the iPhone for the details (app, title, text) of the next notification in the queue. */
    private void pumpRequests() {
        if (inFlight != null || pending.isEmpty() || gatt == null || cpChar == null) return;
        inFlight = pending.poll();
        dsBuf.reset();
        int uid = inFlight.uid;
        byte[] cmd = {
                0,                                  // CommandID: Get Notification Attributes
                (byte) uid, (byte) (uid >> 8), (byte) (uid >> 16), (byte) (uid >> 24),
                0,                                  // App Identifier
                1, 64, 0,                           // Title, max 64 bytes
                3, (byte) 200, 0                    // Message, max 200 bytes
        };
        cpChar.setValue(cmd);
        cpChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!gatt.writeCharacteristic(cpChar)) {
            log("שליחת בקשה לאייפון נכשלה, מנסה שוב");
            pending.addFirst(inFlight);
            inFlight = null;
            h.postDelayed(this::pumpRequests, 300);
            return;
        }
        h.removeCallbacks(requestTimeout);
        h.postDelayed(requestTimeout, 5000);
    }

    private final Runnable requestTimeout = () -> {
        if (inFlight != null) {
            log("תשובה מהאייפון לא הגיעה בזמן");
            finishInFlight();
        }
    };

    private void finishInFlight() {
        h.removeCallbacks(requestTimeout);
        inFlight = null;
        dsBuf.reset();
        pumpRequests();
    }

    /** Data Source: the answer to our request. May arrive in several pieces, so we collect them. */
    private void handleDataSource(byte[] v) {
        if (inFlight == null) return;
        dsBuf.write(v, 0, v.length);
        byte[] b = dsBuf.toByteArray();
        if (b.length < 5) return;

        int pos = 5; // skip CommandID (1) + NotificationUID (4)
        int found = 0;
        while (found < 3) {
            if (pos + 3 > b.length) return; // wait for more data
            int id = b[pos] & 0xff;
            int len = (b[pos + 1] & 0xff) | ((b[pos + 2] & 0xff) << 8);
            if (pos + 3 + len > b.length) return; // wait for more data
            String s = new String(b, pos + 3, len, StandardCharsets.UTF_8);
            if (id == 0) inFlight.appId = s;
            else if (id == 1) inFlight.title = s;
            else if (id == 3) inFlight.message = s;
            pos += 3 + len;
            found++;
        }

        Notif done = inFlight;
        log("🔔 " + done.appName() + " | " + done.title + " | " + done.message
                + (done.preExisting ? "  (התראה ישנה)" : ""));
        listener.onNotification(done);
        finishInFlight();
    }

    // ------------------------------------------------------------------ pairing

    private void bond() {
        if (phone == null) return;
        try {
            Method m = BluetoothDevice.class.getMethod("createBond", int.class);
            m.invoke(phone, TRANSPORT_LE);
        } catch (Exception e) {
            phone.createBond();
        }
    }

    private void registerBondReceiver() {
        if (receiverRegistered) return;
        ctx.registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        receiverRegistered = true;
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int st = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
            if (d == null || phone == null || !d.equals(phone)) return;
            if (st == BluetoothDevice.BOND_BONDED) {
                log("✓ הצימוד הצליח");
                h.postDelayed(() -> {
                    if (gatt != null) discover(gatt);
                }, 1000);
            } else if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                log("✗ הצימוד נכשל או בוטל");
                state("הצימוד נכשל");
            } else if (st == BluetoothDevice.BOND_BONDING) {
                log("מצמד...");
            }
        }
    };

    // ------------------------------------------------------------------ helpers

    /** Clears Android's cached list of the iPhone's services (hidden API, so we use reflection). */
    private void refreshCache(BluetoothGatt g) {
        try {
            Method m = g.getClass().getMethod("refresh");
            m.invoke(g);
        } catch (Exception ignored) {
        }
    }

    private void log(String msg) {
        listener.onLog(msg);
    }

    private void state(String s) {
        listener.onState(s);
    }

    private void fail(String msg) {
        log("✗ " + msg);
        state("נכשל: " + msg);
    }
}
