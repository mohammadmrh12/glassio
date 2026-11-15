package com.glasslauncher.tv.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Run by the TV's own shell (over TV access, ADB) with app_process: the shell may connect and disconnect Bluetooth
 * devices, an app may not. "list" prints one paired device a line (address|name|connected|battery|kind);
 * "connect ADDRESS" / "disconnect ADDRESS" print the result.
 */
@android.annotation.SuppressLint({"BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "MissingPermission", "SoonBlockedPrivateApi"})
public final class BtShell {
    public static void main(String[] args) {
        try {
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
            BluetoothAdapter a = adapter();
            if (a == null) { System.out.println("ERR no adapter"); return; }
            String cmd = args.length > 0 ? args[0] : "list";
            if (cmd.equals("list")) {
                Set<BluetoothDevice> bonded = a.getBondedDevices();
                if (bonded != null) for (BluetoothDevice d : bonded) {
                    boolean on = call(d, "isConnected") instanceof Boolean && (Boolean) call(d, "isConnected");
                    Object bat = call(d, "getBatteryLevel");
                    int kind = d.getBluetoothClass() != null ? d.getBluetoothClass().getMajorDeviceClass() : 0;
                    String alias = android.os.Build.VERSION.SDK_INT >= 30 ? d.getAlias() : null;
                    String name = alias != null ? alias : d.getName();
                    System.out.println(d.getAddress() + "|" + (name == null ? "" : name.replace('|', ' ')) + "|" + on + "|" + (bat == null ? -1 : bat) + "|" + kind);
                }
                System.out.println("OK");
            } else if (args.length > 1) {
                BluetoothDevice d = a.getRemoteDevice(args[1]);
                Object r = call(d, cmd.equals("connect") ? "connect" : "disconnect");
                System.out.println("OK " + r);
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t);
        }
        System.exit(0);
    }

    private static BluetoothAdapter adapter() throws Exception {
        // Outside an app the Bluetooth service isn't looked up by itself: told where it is, as an app's start does.
        try {
            Class<?> sm = Class.forName("android.os.BluetoothServiceManager");
            Class.forName("android.bluetooth.BluetoothFrameworkInitializer").getMethod("setBluetoothServiceManager", sm)
                .invoke(null, sm.getConstructor().newInstance());
        } catch (Throwable ignored) { }
        // As the shell itself (its permissions are checked against its package).
        try {
            Class<?> asb = Class.forName("android.content.AttributionSource$Builder");
            Object b = asb.getConstructor(int.class).newInstance(android.os.Process.myUid());
            asb.getMethod("setPackageName", String.class).invoke(b, "com.android.shell");
            Object src = asb.getMethod("build").invoke(b);
            Method m = BluetoothAdapter.class.getDeclaredMethod("createAdapter", Class.forName("android.content.AttributionSource"));
            m.setAccessible(true);
            return (BluetoothAdapter) m.invoke(null, src);
        } catch (Throwable t) {
            return BluetoothAdapter.getDefaultAdapter();
        }
    }

    private static Object call(BluetoothDevice d, String name) {
        try { Method m = BluetoothDevice.class.getMethod(name); return m.invoke(d); } catch (Throwable t) { return null; }
    }
}
