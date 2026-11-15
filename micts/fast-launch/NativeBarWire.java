package dev.padhelper.fastcts;

import android.os.*;

/** Fixed payloads avoid strings, arbitrary commands, and oversized log records. */
final class NativeBarWire {
    static void send(IBinder peer, int code, long epoch, long time) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(NativeBarProtocol.DESCRIPTOR);
            data.writeLong(epoch); data.writeLong(time);
            if (!peer.transact(code, data, null, IBinder.FLAG_ONEWAY)) throw new RemoteException("Native bar transaction rejected");
        } finally { data.recycle(); }
    }
}
