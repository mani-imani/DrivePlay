package ir.maniimani.driveplay;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Tiny client for the head unit's vendor "car" service (android.car.server), used to hand
 * the audio channel and steering-wheel keys over to us when playback starts:
 *  - pause Bluetooth music if the phone is playing, so two streams never overlap
 *  - leave the Bluetooth/radio source for the generic "other app" source, which is what the
 *    car service itself does for third-party players; steering-wheel keys then arrive as
 *    standard Android media keys on our MediaSession.
 * Every call is best-effort: on a unit without this service nothing happens.
 */
final class CarSource {

    private static final String DESCRIPTOR = "android.car.server.ICarService";
    private static final int SOURCE_EMPTY = 65539;   // AppDefine.APP_ID_EmptySource ("other app")
    private static final int SOURCE_BT_MUSIC = 65537;

    private IBinder binder;

    private synchronized boolean connect() {
        if (binder != null && binder.isBinderAlive()) return true;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "car");
        } catch (Throwable t) {
            binder = null;
        }
        return binder != null;
    }

    /** Call from a background thread right before playback starts. */
    void takeOver() {
        if (!connect()) return;
        try {
            int source = getInt("state.app.cur_source", 0);
            if (source == SOURCE_BT_MUSIC && getInt("state.bt.a2dp_state", 0) == 1) {
                oneway("request.bt.a2dp_play_pause", null);
            }
            if (source != 0 && source != SOURCE_EMPTY) {
                Bundle b = new Bundle();
                b.putInt("app_id", SOURCE_EMPTY);
                b.putString("reason", "DrivePlay");
                sync("request.app.enter_app", b);
            }
        } catch (Throwable ignored) {
        }
    }

    private int getInt(String key, int def) {
        Bundle b = new Bundle();
        b.putString("key", key);
        b.putInt("type", 2);
        sync("request.dm.get_value", b);
        return b.getInt("value", def);
    }

    private int sync(String what, Bundle inout) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(what);
            d.writeInt(0);
            d.writeInt(0);
            d.writeString(null);
            d.writeInt(1);
            inout.writeToParcel(d, 0);
            d.writeInt(0);
            binder.transact(5, d, r, 0);
            r.readException();
            int res = r.readInt();
            if (r.readInt() != 0) inout.readFromParcel(r);
            return res;
        } catch (Throwable t) {
            return 0;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private void oneway(String what, Bundle extras) {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(what);
            d.writeInt(0);
            d.writeInt(0);
            d.writeString(null);
            if (extras != null) {
                d.writeInt(1);
                extras.writeToParcel(d, 0);
            } else {
                d.writeInt(0);
            }
            d.writeInt(0);
            binder.transact(6, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
        }
    }
}
