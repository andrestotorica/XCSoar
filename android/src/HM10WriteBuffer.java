// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar;

import java.util.Arrays;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;

/**
 * This class helps with writing chunked data to a Bluetooth LE HM10
 * device.
 */
final class HM10WriteBuffer {
  private static final String TAG = "XCSoar";

  private static final int MAX_WRITE_CHUNK_SIZE = 20;

  private byte[][] pendingWriteChunks = null;
  private int nextWriteChunkIdx;
  private boolean lastChunkWriteError;

  /**
   * Is the BluetoothGatt object currently busy, i.e. did we call
   * readCharacteristic() or writeCharacteristic() and are we waiting
   * for a beginWriteNextChunk() call from HM10Port?
   *
   * We need to track this because only one pending
   * readCharacteristic()/writeCharacteristic() operation is allowed,
   * and the second one will fail with
   * BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY.
   */
  private boolean gattBusy = false;

  synchronized void reset() {
    lastChunkWriteError = false;
    gattBusy = false;
    clear();
  }

  synchronized boolean beginWriteNextChunk(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic dataCharacteristic) {
    gattBusy = false;

    if (pendingWriteChunks == null)
      return false;

    dataCharacteristic.setValue(pendingWriteChunks[nextWriteChunkIdx]);
    if (!gatt.writeCharacteristic(dataCharacteristic)) {
      Log.e(TAG, "GATT characteristic write request failed");
      setError();
      return false;
    }

    gattBusy = true;

    ++nextWriteChunkIdx;
    if (nextWriteChunkIdx >= pendingWriteChunks.length) {
      /* writing is done */
      clear();
    }

    return true;
  }

  private synchronized void clear() {
    pendingWriteChunks = null;
    notifyAll();
  }

  synchronized void setError() {
    lastChunkWriteError = true;
    gattBusy = false;
    clear();
  }

  synchronized boolean drain() {
    final long TIMEOUT = 5000;
    final long waitUntil = System.currentTimeMillis() + TIMEOUT;

    if (lastChunkWriteError) {
      /* the last write() failed asynchronously; throw this error now
         so the caller knows something went wrong */
      lastChunkWriteError = false;
      return false;
    }

    while (pendingWriteChunks != null) {
      final long timeToWait = waitUntil - System.currentTimeMillis();
      if (timeToWait <= 0)
        return false;

      try {
        wait(timeToWait);
      } catch (InterruptedException e) {
        return false;
      }
    }

    return true;
  }

  synchronized int write(BluetoothGatt gatt,
                         BluetoothGattCharacteristic dataCharacteristic,
                         BluetoothGattCharacteristic deviceNameCharacteristic,
                         byte[] data, int length) {
    final long TIMEOUT = 5000;

    if (0 == length)
      return 0;

    if (!drain())
      return 0;

    if ((dataCharacteristic == null) || (deviceNameCharacteristic == null))
      return 0;

    /* Write data in 20 byte large chunks at maximun. Most GATT devices do
       not support characteristic values which are larger than 20 bytes. */
    int writeChunksCount = (length + MAX_WRITE_CHUNK_SIZE - 1)
      / MAX_WRITE_CHUNK_SIZE;
    pendingWriteChunks = new byte[writeChunksCount][];
    nextWriteChunkIdx = 0;
    for (int i = 0; i < writeChunksCount; ++i) {
      pendingWriteChunks[i] = Arrays.copyOfRange(data,
                                                 i * MAX_WRITE_CHUNK_SIZE,
                                                 Math.min((i + 1) * MAX_WRITE_CHUNK_SIZE,
                                                          length));
    }

    /* Workaround: To avoid a race condition when data is sent and received
       at the same time, we place a read request for the device name
       characteristic here. This way, we can place the actual write
       operation in the read callback so that the write operation is performed
       int the GATT event handling thread. */
    if (!gattBusy) {
      if (!gatt.readCharacteristic(deviceNameCharacteristic)) {
        Log.e(TAG, "GATT characteristic read request failed");
        clear();
        return 0;
      }

      gattBusy = true;
    }

    return length;
  }
}
