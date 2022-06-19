package app.trigger.nuki

import app.trigger.Utils.byteArrayToHexString
import app.trigger.DoorReply.ReplyCode
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import app.trigger.*
import java.util.*

internal abstract class NukiCallback(protected val setup_id: Int, protected val listener: OnTaskCompleted, private val service_uuid: UUID, private val characteristic_uuid: UUID) : BluetoothGattCallback() {
    /*
     * This is mostly called to end the connection quickly instead
     * of waiting for the other side to close the connection
    */
    protected fun closeConnection(gatt: BluetoothGatt) {
        Log.d(TAG, "closeConnection")
        gatt.close()
        NukiRequestHandler.Companion.bluetooth_in_use.set(false)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        Log.d(TAG, "onConnectionStateChange, status: "
                + NukiRequestHandler.Companion.getGattStatus(status)
                + ", newState: " + NukiRequestHandler.Companion.getGattState(newState))
        if (status == BluetoothGatt.GATT_SUCCESS) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothGatt.STATE_CONNECTING -> {}
                BluetoothGatt.STATE_DISCONNECTED -> closeConnection(gatt)
                BluetoothGatt.STATE_DISCONNECTING -> closeConnection(gatt)
                else -> closeConnection(gatt)
            }
        } else {
            closeConnection(gatt)
            listener.onTaskResult(
                    setup_id, ReplyCode.REMOTE_ERROR, "Connection error: " + NukiRequestHandler.Companion.getGattStatus(status)
            )
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.d(TAG, "onServicesDiscovered")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val service = gatt.getService(service_uuid)
            if (service == null) {
                closeConnection(gatt)
                listener.onTaskResult(
                        setup_id, ReplyCode.REMOTE_ERROR, "Service not found: " + service_uuid
                )
                return
            }
            val characteristic = service.getCharacteristic(characteristic_uuid)
            if (characteristic == null) {
                closeConnection(gatt)
                listener.onTaskResult(
                        setup_id, ReplyCode.REMOTE_ERROR, "Characteristic not found: " + characteristic_uuid
                )
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor == null) {
                closeConnection(gatt)
                listener.onTaskResult(
                        setup_id, ReplyCode.REMOTE_ERROR, "Descriptor not found: " + CCC_DESCRIPTOR_UUID
                )
                return
            }

            //Log.i(TAG, "characteristic properties: " + NukiTools.getProperties(characteristic));
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val ok = gatt.writeDescriptor(descriptor)
            if (!ok) {
                Log.e(TAG, "descriptor write failed")
                closeConnection(gatt)
            }
        } else {
            closeConnection(gatt)
            listener.onTaskResult(
                    setup_id, ReplyCode.LOCAL_ERROR, "Client not found: " + NukiRequestHandler.Companion.getGattStatus(status)
            )
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        Log.d(TAG, "onDescriptorWrite, uiid: " + descriptor.uuid + ": " + byteArrayToHexString(descriptor.value))
        if (status == BluetoothGatt.GATT_SUCCESS) {
            onConnected(gatt, descriptor.characteristic)
        } else {
            closeConnection(gatt)
            Log.e(TAG, "failed to write to client: " + NukiRequestHandler.Companion.getGattStatus(status))
        }
    }

    abstract fun onConnected(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic)

    companion object {
        private const val TAG = "NukiCallback"

        // Client Characteristic Configuration Descriptor
        val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Pairing UUIDs
        val PAIRING_SERVICE_UUID = UUID.fromString("a92ee100-5501-11e4-916c-0800200c9a66")
        val PAIRING_GDIO_XTERISTIC_UUID = UUID.fromString("a92ee101-5501-11e4-916c-0800200c9a66")

        // Keyturner UUIDs
        val KEYTURNER_SERVICE_UUID = UUID.fromString("a92ee200-5501-11e4-916c-0800200c9a66")
        val KEYTURNER_GDIO_XTERISTIC_UUID = UUID.fromString("a92ee201-5501-11e4-916c-0800200c9a66")
        val KEYTURNER_USDIO_XTERISTIC_UUID = UUID.fromString("a92ee202-5501-11e4-916c-0800200c9a66")
    }
}