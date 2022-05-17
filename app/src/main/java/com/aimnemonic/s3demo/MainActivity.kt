@file:Suppress("SpellCheckingInspection", "PrivatePropertyName", "LocalVariableName",
    "PropertyName")

package com.aimnemonic.s3demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.util.*

class MainActivity : AppCompatActivity() {

    // <------------------------------------- Variables ------------------------------------->

    // THE UUID OF THE AIM HEADBAND WHICH IS THE BLUETOOTH ID
    // THE UUID SHOULD BE THE SAME FOR *ALL AIM HEADBANDS* BECAUSE IT'S THE FILTERING CRITERIA FOR BLUETOOTH SCANNING
//    private val AIM_HEADBAND_UUID: UUID = UUID.fromString("2559c4c5-aff1-47de-8c4f-af465b74ef86")

    // CONFIGURATION CONSTANTS
    // GOOGLE THE VARIABLE NAME TO READ DOCUMENTATIONS FOR FURTHER INFORMATION
    private val ENABLE_BLUETOOTH_REQUEST_CODE = 1
    private val LOCATION_PERMISSION_REQUEST_CODE = 2
    private val WRITE_PERMISSION_REQUEST_CODE = 3
    private val READ_PERMISSION_REQUEST_CODE = 4
    private val BLUETOOTH_WRITE_TYPE = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private val GATT_MAX_MTU_SIZE = 517

    // THE UUID (BLUETOOTH ID) FOR THE SERVICES AND CHARACTERISTICS
    private val MAIN_SERVICE_UUID : UUID = UUID.fromString("675df09c-378f-498c-a73d-51e107bb152b")
    private val MAIN_CHAR_UUID    : UUID = UUID.fromString("68c05225-74f2-420a-aa84-c5ce1174f685")
    private val DATA_CHAR_UUID    : UUID = UUID.fromString("5e9c9efb-c5c2-414c-b0d8-9bfefb0f13c9")

    // A TAG ASSOCIATED WITH ALL PRINTED MESSAGES ON THE LOG
    private val TAG = "tag"

    //  GLOABL VARIABLE TO INDICATE WHETHER BLE SCANNING IS ON/OFF
    private var isScanning = false

    // GLOBAL VARIABLE TO THE STATE OF CONNECTION
    private var connection_status = false // TRUE = CONNECTED - FALSE = NOT CONNECTED
        set(value) {
            field = value
            if(value)
                viewPresetSetClickDB()
            else
                viewPresetNonClickDB()
        }

    // GLOBAL VARIABLE TO THE BOOLEAN INDICATING WHETHER THE COMMUNICATION LINK IS FREE OR BUSY
    private var link_status = true // TRUE = FREE - FALSE = BUSY
        set(value) {
            field = value
            if(value)
                handleLink()
        }

    // Time variables
    private var oldTime = ""
    private var newTime = ""

    // Packet variables
    var packet_count = 0

    // Data array
    private var data_array = mutableListOf<ByteArray>()

    // GLOBAL REFERENCES TO VIEWS
    private lateinit var scan_result_recycler_view : RecyclerView
    private lateinit var disconnectButton : Button
    private lateinit var refreshButton: Button
    private lateinit var bulkButton: Button
    private lateinit var randomButton: Button
    private lateinit var clearButton: Button
    private lateinit var status_textview : TextView
    private lateinit var packet_textview : TextView
    private lateinit var time_textview : TextView
    private lateinit var device_textview : TextView
    private lateinit var textbox : TextView

    // INITIALISATION OF VIEWS
    private fun viewsInit() {
        scan_result_recycler_view = findViewById(R.id.scan_results_recycler_view)
        disconnectButton = findViewById(R.id.disconnect_button)
        refreshButton = findViewById(R.id.refresh_button)
        bulkButton = findViewById(R.id.bulk_button)
        randomButton = findViewById(R.id.random_button)
        clearButton = findViewById(R.id.clear_button)
        status_textview = findViewById(R.id.status_textview)
        packet_textview = findViewById(R.id.packet_textview)
        time_textview = findViewById(R.id.time_textview)
        device_textview = findViewById(R.id.device_textview)
        textbox = findViewById(R.id.textbox)
    }

    // OVERRIDE FUNCTION : onCreate (occurs everytime activity is started)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // INITIALISATION OF VIEWS
        viewsInit()

        // SETUP THE RECYCLERVIEW FOR THE SCANNED BLE DEVICES
        setupRecyclerView()

        // DISCONNECT BUTTON CLICK LISTENER
        disconnectButton.setOnClickListener {
            bluetoothGatt.disconnect()
            startBleScan()
        }
        refreshButton.setOnClickListener {
            bleScanner.stopScan(scanCallback)
            startBleScan()
        }
        bulkButton.setOnClickListener {
            if(connection_status)
                addLinkQueue("Send Bulk")
            else
                Toast.makeText(this, R.string.status_not_connected, Toast.LENGTH_SHORT).show()
        }
        randomButton.setOnClickListener {
            if(connection_status)
                addLinkQueue("Generate new Numbers")
            else
                Toast.makeText(this, R.string.status_not_connected, Toast.LENGTH_SHORT).show()
        }
        clearButton.setOnClickListener {
            runOnUiThread {
                textbox.text = getText(R.string.empty)
            }
        }
    }

    // OVERRIDE FUNCTION : onResume (occurs everytime activity is resumed)
    // This may be called more than once if the user exits the activity and comes back to it
    // It is useful to check Bluetooth permission to make sure that Bluetooth is turned on as long as this activity is active
    override fun onResume() {
        super.onResume()
        if(!isWritePermissionGranted) {
            requestWritePermission()
        } else {
            promptEnableBluetooth()
        }
    }

    // <------------------------------------- Bluetooth & Location Permission ------------------------------------->
    // Bluetooth Manager is the Bluetooth Module inside every android device
    // Bluetooth Adapter is the object responsible for handling the Bluetooth functionality
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // GLOBAL VARIABLE TO DETERMINE WHETHER THE ACTIVITY HAS PERMISSION TO READ AND WRITE FILES
    // THIS PART IS NECESSARY TO WRITING FILES LOCALLY ON THE ANDROID STORAGE
    private val isWritePermissionGranted
        get() = hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private val isReadPermissionGranted
        get() = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)

    // FUNCTION TO REQUEST USER TO TURN ON BLUETOOTH
    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        } else {
            startBleScan()
        }
    }

    // OVERRIDE FUNCTION : onActivityResult
    // FUNCTION RESPONSIBLE FOR DEALING WITH THE ANSWER OF BLUETOOTH REQUEST
    // IF USER DOES ALLOW BLUETOOTH --> DO NOTHING
    // IF USER DONT ALLOW BLUETOOTH --> ASK SAME QUESTION AGAIN
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ENABLE_BLUETOOTH_REQUEST_CODE -> {
                if (resultCode != Activity.RESULT_OK)
                    promptEnableBluetooth()
                else
                    startBleScan()
            }
        }
    }

    // GLOBAL VARIABLE TO DETERMINE WHETHER THE ACTIVITY HAS PERMISSION TO GET LOCATION
    // THIS PART IS NECESSARY FOR BLUETOOTH TO FUNCTION
    // WITHOUT IT, BLUETOOTH WOULD BE TURNED ON BUT NO DEVICES WOULD BE SCANNED WITHOUT ANY ERROR MESSAGES
    // TODO : VALIDATE WHETHER NEW HUAWEI PHONES NEED USER TO ALLOW LOCATION MANUALLY
    private val isLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    // FUNCTION TO CHECK WHETHER PERMISSION IS GRANTED FOR ANY PERMISSION
    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    // FUNCTION TO REQUEST LOCATION PERMISSION
    private fun requestLocationPermission() {

        // IF PERMISSION IS GRANTED --> RETURN
        if (isLocationPermissionGranted) {
            return
        }

        // CREATE THE FUNCTION FOR THE POSITIVE CLICK
        // THIS FUNCTION TURN BLUETOOTH ON
        val posClick = { _: DialogInterface, _: Int ->
            requestPermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // DISPLAY AN ALERT TO REQUEST USER TO TURN BLUETOOTH ON
        // THIS IS DONE AS GOOD PRACTICE AND IS FOLLOWED BY MOST DEPLOYED APPLICATIONS
        runOnUiThread {
            val alert = AlertDialog.Builder(this)
            alert.setTitle("Location permission required")
            alert.setMessage("Starting from Android M (6.0), the system requires apps to be granted " +
                    "location access in order to scan for BLE devices.")
            alert.setCancelable(false)
            alert.setPositiveButton("OK", DialogInterface.OnClickListener(function = posClick))
            alert.show()
        }
    }

    // FUNCTION TO REQUEST ANY GIVEN PERMISSION
    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    // OVERRIDE FUNCTION : onRequestPermissionResult
    // FUNCTION RESPONSIBLE FOR DEALING WITH THE ANSWER OF LOCATION, READ AND WRITE REQUESTS
    // IF USER DOES ALLOW LOCATION --> DO NOTHING
    // IF USER DONT ALLOW LOCATION --> ASK SAME QUESTION AGAIN
    // IF USER DOES ALLOW READ --> DO NOTHING
    // IF USER DONT ALLOW READ --> ASK SAME QUESTION AGAIN
    // IF USER DOES ALLOW WRITE --> DO NOTHING
    // IF USER DONT ALLOW WRITE --> ASK SAME QUESTION AGAIN
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestLocationPermission()
                }
            }
            WRITE_PERMISSION_REQUEST_CODE ->{
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestWritePermission()
                }
            }
            READ_PERMISSION_REQUEST_CODE ->{
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestReadPermission()
                }
            }
        }
    }

    // FUNCTION TO REQUEST WRITE PERMISSION
    // SAME LOGIC AS LOCATION
    private fun requestWritePermission() {
        if (isWritePermissionGranted) {
            promptEnableBluetooth()
            return
        }
        val posClick = { _: DialogInterface, _: Int ->
            requestPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                WRITE_PERMISSION_REQUEST_CODE
            )
        }
        runOnUiThread {
            val alert = AlertDialog.Builder(this)
            alert.setTitle("Write permission required")
            alert.setMessage("Data logging requires writing permission.")
            alert.setCancelable(false)
            alert.setPositiveButton("OK", DialogInterface.OnClickListener(function = posClick))

            alert.show()
        }
    }

    // FUNCTION TO REQUEST READ PERMISSION
    // SAME LOGIC AS LOCATION
    private fun requestReadPermission() {
        if (isReadPermissionGranted) {
            return
        }
        val posClick = { _: DialogInterface, _: Int ->
            requestPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                READ_PERMISSION_REQUEST_CODE
            )
        }
        runOnUiThread {
            val alert = AlertDialog.Builder(this)
            alert.setTitle("Read permission required")
            alert.setMessage("Data logging requires reading permission.")
            alert.setCancelable(false)
            alert.setPositiveButton("OK", DialogInterface.OnClickListener(function = posClick))

            alert.show()
        }
    }

    // <------------------------------------- Start Scan ------------------------------------->
    // Bluetooth Adapter is the object responsible for handling the Bluetooth functionality
    // Bluetooth Scanner is the object responsible for scanning Bluetooth devices
    // by lazy --> The object will be initialised when it is called not upon startup
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    // FUNCTION RESPONSIBLE FOR SCANNING BLUETOOTH DEVICES
    private fun startBleScan() {

        // CHECK IF DEVICE NEEDS LOCATION PERMISSION
        // DEVICE NEEDS LOCATION PERMISSION IF THE ANDROID VERSION IS MARSHMALLOW OR LATER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            requestLocationPermission()
        }

        // CLEAR SCAN RESULTS AND UPDATE RECYCLERVIEW
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()

        // AI MNEMONIC FILTERED SCAN
        bleScanner.startScan(listOf(filter), scanSettings, scanCallback)

        // NO FILTER SCAN -- UNCOMMENT NEXT LINE IF NEEDED
        // bleScanner.startScan(null, scanSettings, scanCallback)

        // UPDATE GLOBAL VARIABLE INDICATING THE SCAN STATUS
        isScanning = true
    }

    // THIS FILTER ALLOWS THE APPLICATION TO SCAN AIM HEADBAND DEVICES ONLY
    private val filter: ScanFilter = ScanFilter.Builder().setServiceUuid(
        ParcelUuid.fromString(MAIN_SERVICE_UUID.toString())
    ).build()

    // THIS SCAN SETTING IS THE MOST OPTIMAL HIGH-POWER BLUETOOTH SCANNING MODE
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // THIS OBJECT HOLDS REFERENCE TO BLUETOOTH SCAN MANAGER
    private val scanCallback = object : ScanCallback() {

        // OVERRIDE FUNCTION : onScanResult
        // UPON FINDING A SCAN RESULT
        override fun onScanResult(callbackType: Int, result: ScanResult) {

            // UPDATE RECYCLERVIEW TO DISPLAY SCAN RESULT
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) { // A SCAN RESULT ALREADY EXISTS WITH THE SAME NAME
                scanResults[indexQuery] = result
                scanResultAdapter.notifyItemChanged(indexQuery)
            } else { // THE SCAN RESULT IS NEW
                with(result.device) {
                    Log.i(TAG, "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                }
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }

        // OVERRIDE FUNCTION : onScanFailed
        // UPON FAILING TO SCAN
        override fun onScanFailed(errorCode: Int) {
            viewPresetBleScanFail(errorCode)
        }
    }

    // FUNCTION RESPONSIBLE FOR STOP SCANNING
    private fun stopBleScan() {

        // THE FUNCTION RESPONSIBLE FOR STOPPING THE SCAN
        bleScanner.stopScan(scanCallback)

        // UPDATE GLOBAL VARIABLE INDICATING THE SCAN STATUS
        isScanning = false

        // CLEAR SCAN RESULTS AND UPDATE RECYCLERVIEW
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()
    }

    // <------------------------------------- Display Scan ------------------------------------->

    // THIS FUNCTION IS RESPONSIBLE FOR SETTING UP THE RECYCLERVIEW
    private fun setupRecyclerView() {

        // THIS BLOCK IS INITIALISING THE ATTRIBUTES WITHIN THE RECYCLERVIEW
        scan_result_recycler_view.apply {

            // THIS IS EQUIVALENT TO --> scan_result_recycler_view.adapter = scanResultAdapter
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(
                this@MainActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }

        val animator = scan_result_recycler_view.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    // THIS GLOBAL LIST HOLDS THE VALUES OF ALL THE SCANNED RESULTS
    private val scanResults = mutableListOf<ScanResult>()
    // THIS GLOBAL VARIABLE IS THE BLE SCANNING ADAPTER
    // BY LAZY MEANS THAT THE VARIABLE WILL BE INITIALISED DURING ITS FIRST CALL
    private val scanResultAdapter: ScanResultAdapter by lazy {
        // THE INITIALISED VALUE WILL BE THE RETURNED OBJECT FROM THE FOLLOWING BLOCK
        ScanResultAdapter(scanResults) { result ->
            // User tapped on a scan result
            if (isScanning) {
                stopBleScan()
                viewPresetConnecting()
            }
            with(result.device) {
                Log.w(TAG, "Connecting to $address")
                // THIS IS THE FUNCTION THAT CONNECTS THE SCANNED DEVICE
                connectGatt(this@MainActivity, true, gattCallback)
                // THIS IS A GLOBAL REFERENCE TO THE CONNECTED DEVICE
                deviceName = result.device.name
            }
        }
    }

    // <------------------------------------- Connection ------------------------------------->

    // THIS IS THE GATT OBJECT WHICH HANDLES COMMUNICATION
    lateinit var bluetoothGatt: BluetoothGatt
    // GLOBAL VARIABLE TO THE CONNECTED DEVICE BUT IT IS INITIALISED LATE
    private lateinit var deviceName : String

    // THIS IS THE MAIN COMMUNICATION OBJECT
    private val gattCallback = object : BluetoothGattCallback() {

        // OVERRIDE FUNCTION : onConnectionStateChange
        // THIS FUNCTION IS CALLED EVERYTIME THE STATE OF CONNECTION IS CHANGED
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            // IF CONNECTION COULD BE IDENTIFIED
            if (status == BluetoothGatt.GATT_SUCCESS) {

                // IF STATE IS CONNECTED
                if (newState == BluetoothProfile.STATE_CONNECTED) {

                    // UPDATE GLOBAL VARIABLE INDICATING THE STATE OF CONNECTION
                    connection_status = true
                    Log.w(TAG, "Successfully connected to $deviceAddress")

                    // UPDATE THE BLUETOOTH GATT RESPONSIBLE FOR COMMUNICATION
                    bluetoothGatt = gatt

                    // REQUEST THE MTU OF CONNECTION
                    gatt.requestMtu(GATT_MAX_MTU_SIZE)
                }

                // IF STATE IS DISCONNECTED (WHICH ONLY OCCURS WHEN YOU CLICK ON THE DISCONNECT BUTTON)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // UPDATE GLOBAL VARIABLE INDICATING THE STATE OF CONNECTION
                    connection_status = false
                    Log.w(TAG, "Successfully disconnected from $deviceAddress")

                    // AS GOOD PRACTICE, GATT SHOULD BE CLOSED AS EARLY AS POSSIBLE FROM CODE
                    gatt.close()
                }
            }

            // IF CONNECTION COULD NOT BE IDENTIFIED (DISCONNECTED WITH ERROR)
            else {
                // UPDATE GLOBAL VARIABLE INDICATING THE STATE OF CONNECTION
                connection_status = false
                Log.w(TAG, "Error $status encountered for $deviceAddress! Disconnecting...")

                // THIS FUNCTION ATTEMPTS TO RECONNECT
                gatt.connect()
            }
        }

        // OVERRIDE FUNCTION : onMtuChanged
        // THIS FUNCTION IS CALLED WHEN MTU IS REQUESTED OR UPDATED
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // DISPLAY THE SIZE OF MTU
            Log.w(TAG, "ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")

            // TODO : PERFORM ERROR HANDLING FOR THE CASE THAT THE MTU OBTAINED IS LESS THAN 500

            // DISCOVER THE BLE SERVICES
            bluetoothGatt.discoverServices()
        }

        // OVERRIDE FUNCTION : onServicesDiscovered
        // THIS FUNCTION IS CALLED WHEN BLE SERVICES ARE DISCOVERED
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt) {
                Log.w(TAG, "Discovered ${services.size} services for ${device.address}")
                printGattTable()

                // THIS FUNCTION IS RESPONSIBLE FOR READING THE CNCT_ERR_CHAR CHARACTERISTIC WITHIN THE CNCT_ERR_SERV SERVICES
                // READ SEQUENCE DIAGRAM FOR MORE INFORMATION
                readCharacteristic(MAIN_SERVICE_UUID, MAIN_CHAR_UUID)
            }
        }

        // OVERRIDE FUNCTION : onCharacteristicRead
        // THIS FUNCTION IS CALLED WHEN BLE CHARACTERISTICS ARE READ FOR THE FIRST TIME
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {

                        // HANDLING OF READ CHARACTERISTIC BASED ON CHARACTERISTIC
                        when (characteristic.uuid) {
//                          ITERATE THROUGH THE READINGS
                            MAIN_CHAR_UUID -> {
                                Log.i(TAG, "Reading CNCT characteristic $uuid:\nString: ${String(value)}")
                                readCharacteristic(MAIN_SERVICE_UUID, DATA_CHAR_UUID)
                            }
                            DATA_CHAR_UUID -> {
                                Log.i(TAG, "Reading EEG characteristic $uuid:\nString: ${String(value)}")
                                viewPresetBleConnected()

                                // Connection is established here
                            }
                        }
                        // ALL CHARACTERISTICS SHARE SUBSCRIBTION FEATURE
                        bluetoothGatt.setCharacteristicNotification(characteristic, true)
                    }
                    // ERROR HANDLING
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Log.e(TAG, "Read not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(TAG, "Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
        }

        // OVERRIDE FUNCTION : onCharacteristicChanged
        // THIS FUNCTION IS CALLED WHEN BLE CHARACTERISTICS ARE CHANGED
        // THIS IS THE MOST IMPORTANT COMMUNICATION FUNCTION
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic) {
                when (uuid) {
                    // RESPONSIBLE FOR ADMIN COMMUNICATIONS AND FUTURE ERROR HANDLING
                    MAIN_CHAR_UUID -> {
                        when {
                            // THIS IS A CONFIRMATION THAT FIRMWARE STARTED FOCUS
                            String(value) == "Message sent successfully" -> {
                                newTime = getDataCycleSeconds()
                                printData()
                            }
                            String(value) == "Verify please" -> {
                                writeCharacteristic("Send Bulk".toByteArray())
                            } else -> {
                                Log.e(TAG, "Value received on CNCT Char is unknown! : ${String(value)}")
                            }
                        }
                    }
                    // RESPONSIBLE FOR RECEIVING IMAGE
                    DATA_CHAR_UUID -> {
                        Log.i(TAG, "Read characteristic $uuid:\n${value.toHexString()}")
                        data_array.add(value)
                        packet_count++
                    }
                    // THERE IS NO WAY THE APPLICATION WOULD DETECT UNKNOWN CHARACTERISTIC UNLESS THE FIRMWARE CODE HAS BEEN TAMPERED WITH
                    else -> {
                        Log.e(TAG, "Error impossible to happen")
                    }
                }
            }
        }

        // OVERRIDE FUNCTION : onCharacteristicWrite
        // THIS FUNCTION IS CALLED WHEN BLE CHARACTERISTIC CNCT_ERR_CHAR IS BEING WRITTEN ON (SENDING TO FIRMWARE)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // THIS CHARACTERISTIC WILL ALWAYS BE THE ADMIN CHARACTERISTIC CNCT_ERR_CHAR
            with(characteristic) {
                when (status) {
                    // IF WRITING WAS A SUCCESS
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.e(TAG, "Wrote to characteristic $uuid | value: ${value.toHexString()}")
                    }
                    // IF WE ATTEMPTED WRITING A MESSAGE THAT'S TOO BIG
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        Log.e(TAG, "Write exceeded connection ATT MTU!")
                    }
                    // IF WE ATTEMPTED WRITING TO A CHARACTERISTIC THAT'S READ ONLY (IMPOSSIBLE TO HAPPEN)
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e(TAG, "Write not permitted for $uuid!")
                    }
                    // OTHER RANDOM ERRORS
                    else -> {
                        Log.e(TAG, "Characteristic write failed for $uuid, error: $status")
                    }
                }
            }
        }
    }

    private fun printData() {
        var bytes = byteArrayOf()
        for(b in data_array) {
            bytes += b
        }
        data_array.clear()

//        var count = 0
        val str = "Hello"
//        while(count < bytes.size) {
//            var str_int : Long = bytes.getUInt42(count).toLong()
//            if(str_int > 32767) {
//                str_int -= 65536
//            }
//            str += "\t$str_int"
//            count += 2
//        }
        viewPresetDisplayMsg(str, getTimeDifference(oldTime, newTime))
    }

    // <------------------------------------- Tools ------------------------------------->
    // PRINT SERVICES INFORMATION TO LOG
    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Log.i(TAG,"No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { it.uuid.toString() }
            Log.i(TAG, "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable"
            )
        }
    }
    // CHECK WHETHER CHARACTERISTIC IS WRITABLE
    private fun BluetoothGattCharacteristic.isReadable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)
    // CHECK WHETHER CHARACTERISTIC IS WRITABLE
    private fun BluetoothGattCharacteristic.isWritable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)
    // CHECK WHETHER CHARACTERISTIC CONTAINS A CERTAIN PROPERTY
    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }
    // FUNCTION RESPONSIBLE FOR READING CHARACTERISTICS
    private fun readCharacteristic(SERVICE_UUID : UUID, CHAR_UUID : UUID) {
        val characteristic = bluetoothGatt.getService(SERVICE_UUID).getCharacteristic(CHAR_UUID)
        if(characteristic?.isReadable() == true) {
            bluetoothGatt.readCharacteristic(characteristic)
        }
    }
    // FUNCTION RESPONSIBLE FOR WRITING ON THE CNCT_ERR_CHAR CHARACTERISTIC
    private fun writeCharacteristic(payload: ByteArray): Boolean {
        val characteristic = bluetoothGatt.getService(MAIN_SERVICE_UUID).getCharacteristic(MAIN_CHAR_UUID)
        return if(characteristic.isWritable()) {
            Log.e(TAG, "writing message: ${String(payload)}")
            bluetoothGatt.let { gatt ->
                characteristic.writeType = BLUETOOTH_WRITE_TYPE
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            }
            true
        } else {
            Log.e(TAG, "characteristic is not Writable!")
            false
        }
    }
    // CONVERT BYTEARRAY TO HEXADECIMAL
    private fun ByteArray.toHexString(): String = joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
    // FUNCTION RESPONSIBLE FOR HANDLING THE DATA STRUCTURE RESPONSIBLE FOR HANDLING COMMUNICATION
    private fun handleLink() {
        if(link_status) {
            val msg = BLELinkMessage.handleLink()
            if(msg.equals("Do nothing!"))
                return
            writeCharacteristic(msg.toByteArray())
            oldTime = getDataCycleSeconds()
        }
    }
    // FUNCTION RESPONSIBLE FOR ADDING A MESSAGE TO BE SENT
    private fun addLinkQueue(msg : String) {
        val linkObject = BLELinkMessage(msg)
        if(BLELinkMessage.addLinkQueue(linkObject))
            handleLink()
        else
            Log.d(TAG, "Can not add message to queue")
    }
    // GET CURRENT TIME
    private fun getDataCycleSeconds() : String {
        val currentTime = Calendar.getInstance()

        val hour_int = currentTime.get(Calendar.HOUR_OF_DAY)
        val minute_int = currentTime.get(Calendar.MINUTE)
        val second_int = currentTime.get(Calendar.SECOND)
        val msecond_int = currentTime.get(Calendar.MILLISECOND)

        var hour : String = hour_int.toString()
        if(hour_int < 10)
            hour = "0$hour_int"

        var minute : String = minute_int.toString()
        if(minute_int < 10)
            minute = "0$minute_int"

        var second : String = second_int.toString()
        if(second_int < 10)
            second = "0$second_int"

        var msecond : String = msecond_int.toString()
        if(msecond_int < 10)
            msecond = "00$msecond_int"
        else if(msecond_int < 100)
            msecond = "0$msecond_int"

        return "$hour$minute$second$msecond"
    }
    // GET DIFFERENCE IN TIME
    private fun getTimeDifference(old : String, new : String) : String {

        val hour1 = old.subSequence(0, 2).toString().toInt()
        val hour2 = new.subSequence(0, 2).toString().toInt()

        val minute1 = old.subSequence(2, 4).toString().toInt()
        val minute2 = new.subSequence(2, 4).toString().toInt()

        val second1 = old.subSequence(4, 6).toString().toInt()
        val second2 = new.subSequence(4, 6).toString().toInt()

        val msecond1 = old.subSequence(6, 9).toString().toInt()
        val msecond2 = new.subSequence(6, 9).toString().toInt()

        var hourD = hour2 - hour1
        var minuteD = minute2 - minute1
        var secondD = second2 - second1
        var msecondD = msecond2 - msecond1

        if(msecondD < 0) {
            msecondD += 1000
            secondD--
        }

        if(secondD < 0) {
            secondD += 60
            minuteD--
        }

        if(minuteD < 0) {
            minuteD += 60
            hourD--
        }

        return "$hourD:$minuteD:$secondD:$msecondD"
    }
    // CONVERT UINT16 TO INTEGER
    private fun ByteArray.getUInt42(idx: Int) = ((this[idx + 1].toInt() and 0xFF) shl 8) or
            ((this[idx].toInt() and 0xFF))

    // <------------------------------------- Views Presets ------------------------------------->
    private fun viewPresetSetClickDB() {
        Log.i(TAG, "Disconnect Button is clickable!")
        runOnUiThread {
            disconnectButton.isEnabled = true
            disconnectButton.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.disconnect_button_clickable)
            refreshButton.isEnabled = false
            refreshButton.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.disconnect_button_non_clickable)
        }
    }
    private fun viewPresetNonClickDB() {
        Log.i(TAG, "Disconnect Button is NOT clickable!")
        runOnUiThread {
            disconnectButton.isEnabled = false
            disconnectButton.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.disconnect_button_non_clickable)
            refreshButton.isEnabled = true
            refreshButton.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.disconnect_button_clickable)
            device_textview.text = getString(R.string.device_not_connected)
            device_textview.setTextColor(getColor(R.color.red))
        }
    }
    private fun viewPresetBleConnected() {
        Log.i(TAG, "Device finished connecting")
        runOnUiThread {
            device_textview.text = deviceName
            device_textview.setTextColor(getColor(R.color.green))
        }
    }
    private fun viewPresetBleScanFail(errorCode : Int) {
        Log.e(TAG, "onScanFailed: code $errorCode")
        runOnUiThread {
            status_textview.text = getString(R.string.scan_failed)
        }
    }
    private fun viewPresetConnecting() {
        Log.i(TAG, "Connecting to Headband...")
        runOnUiThread {
            device_textview.setText(R.string.connecting)
            device_textview.setTextColor(getColor(R.color.orange))
        }
    }
    private fun viewPresetDisplayMsg(msg : String, time : String) {
        Log.i(TAG, "Displaying message")
        runOnUiThread {
            textbox.text = msg
            packet_textview.text = packet_count.toString()
            time_textview.text = time
            packet_count = 0
        }
    }
}