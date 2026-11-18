package com.prelina.findmynet

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapActivity - map page (Batch 2: MapLibre + OpenStreetMap, Mac-only)
 *
 * Shows the saved target device (normally the user's Mac) on a MapLibre
 * map fed by OpenStreetMap raster tiles, with the device list in the
 * bottom sheet. Coordinates are WGS84 straight from FindMyService;
 * no conversion is applied (the GCJ-02 converter and the old map
 * SDK were deleted in batches 1 and 2 respectively).
 *
 * Refresh: while this activity is in the foreground the target device
 * is re-located every [REFRESH_INTERVAL_MS]. The interval was lengthened
 * from the upstream 30 s to 120 s in batch 2: only one device is located
 * now (server-side, via selectedDevice), so 30 s polling bought nothing
 * but extra requests against Apple's rate limits and extra battery use.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MapActivity"

        // Batch 2: 120 s (was 30 s upstream) - see class doc above.
        private const val REFRESH_INTERVAL_MS = 120_000L

        // Minimal inline MapLibre style: a single OSM raster source.
        // No API key and no third-party style host are needed, and the
        // tiles go straight from tile.openstreetmap.org to the device.
        // The source carries the OSM attribution string, and MapLibre's
        // attribution control is left enabled in initMap(), so the
        // "(c) OpenStreetMap contributors" credit is reachable from the
        // map as the OSM tile licence requires.
        private const val OSM_STYLE_JSON = """
            {
              "version": 8,
              "name": "OpenStreetMap (raster)",
              "sources": {
                "osm": {
                  "type": "raster",
                  "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                  "tileSize": 256,
                  "attribution": "© OpenStreetMap contributors"
                }
              },
              "layers": [
                { "id": "osm", "type": "raster", "source": "osm" }
              ]
            }
        """
    }

    private var mapLibreMap: MapLibreMap? = null
    private lateinit var mapView: MapView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<*>
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var findMyService: FindMyService
    private lateinit var iCloudService: ICloudService
    private lateinit var targetStore: TargetDeviceStore

    // UI components
    private lateinit var toolbar: Toolbar
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var btnChangeTarget: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvDeviceCount: TextView
    private lateinit var progressBar: ProgressBar

    // Map markers, keyed by DeviceData.id
    private val markers = mutableMapOf<String, Marker>()

    // Devices waiting to be drawn because the map/style was not ready yet
    private var pendingMarkerDevices: List<DeviceData>? = null
    private var isStyleLoaded = false

    // Last unfiltered device list seen, used by the target picker
    private var lastFetchedDevices: List<DeviceData> = emptyList()
    private var firstRunPickerShown = false

    // Foreground auto-refresh
    private val handler = Handler(Looper.getMainLooper())
    private var isAutoRefreshEnabled = true
    private var refreshCount = 0

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (isAutoRefreshEnabled) {
                refreshCount++
                refreshDevices()
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MapLibre must be initialised before the MapView is inflated.
        MapLibre.getInstance(this)

        setContentView(R.layout.activity_map)

        iCloudService = ICloudService.getInstance()
        findMyService = FindMyService(iCloudService)
        targetStore = TargetDeviceStore(applicationContext)

        initViews()
        initMap(savedInstanceState)
        initBottomSheet()
        initRecyclerView()

        refreshDevices()
    }

    // --- MapLibre MapView lifecycle forwarding (all required) ---

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        stopAutoRefresh()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        stopAutoRefresh()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private fun startAutoRefresh() {
        if (!isAutoRefreshEnabled) {
            isAutoRefreshEnabled = true
            refreshCount = 0
            // First auto refresh is delayed: onCreate already refreshed.
            handler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS)
        }
    }

    private fun stopAutoRefresh() {
        if (isAutoRefreshEnabled) {
            isAutoRefreshEnabled = false
            handler.removeCallbacks(autoRefreshRunnable)
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        fabRefresh = findViewById(R.id.fabRefresh)
        btnChangeTarget = findViewById(R.id.btnChangeTarget)
        recyclerView = findViewById(R.id.recyclerViewDevices)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        progressBar = findViewById(R.id.progressBar)
        mapView = findViewById(R.id.mapView)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        fabRefresh.setOnClickListener {
            refreshDevices()
        }

        // Change the target device later: fetch the full device list
        // (no selectedDevice narrowing) and open the picker again.
        btnChangeTarget.setOnClickListener {
            fetchDevicesForPicker()
        }
    }

    private fun initMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map

            // Keep the attribution control (and logo) enabled: the OSM
            // tile licence requires attribution, and the style source
            // carries the credit string shown by that control.
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = true
            map.uiSettings.isZoomGesturesEnabled = true
            map.uiSettings.isScrollGesturesEnabled = true
            map.uiSettings.isCompassEnabled = true

            // NOTE: no own-location layer. The phone's position is not
            // needed to show the Mac, so no location permission is
            // requested anywhere in this app.

            map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) {
                isStyleLoaded = true
                // Draw anything that arrived before the style was ready.
                pendingMarkerDevices?.let { devices ->
                    pendingMarkerDevices = null
                    updateMapMarkers(devices)
                }
            }

            map.setOnMarkerClickListener { marker ->
                val deviceId = markers.entries.firstOrNull { it.value == marker }?.key
                deviceId?.let { highlightDeviceInList(it) }
                // Return false so MapLibre also shows the info window.
                false
            }
        }
    }

    private fun initBottomSheet() {
        val bottomSheet = findViewById<View>(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        bottomSheetBehavior.isDraggable = true
        bottomSheetBehavior.addBottomSheetCallback(object :
            BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
            }
        })
    }

    private fun initRecyclerView() {
        deviceAdapter = DeviceAdapter(
            onDeviceClick = { device ->
            },
            onPlaySoundClick = { device ->
                playSound(device)
            },
            onShowOnMapClick = { device ->
                showDeviceOnMap(device)
            }
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MapActivity)
            adapter = deviceAdapter
        }
    }

    /**
     * Marker click -> device list: expand the bottom sheet, scroll to
     * the device row and expand it.
     */
    private fun highlightDeviceInList(deviceId: String) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        val position = deviceAdapter.expandDevice(deviceId)
        if (position >= 0) {
            recyclerView.scrollToPosition(position)
        }
    }

    /**
     * Refresh the target device's location.
     *
     * Batch 2 (Mac-only narrowing):
     * - fmly is always false, so family-shared devices are not located.
     * - Once a target device id is saved, it is passed to
     *   FindMyService.refreshDevices() as selectedDevice, so Apple is
     *   asked to locate only that device.
     * - The displayed list/map are additionally keep-filtered client-side
     *   by DeviceFilter (by id, falling back to deviceClass == "mac").
     * - On the first run (no saved target) the unfiltered list is shown
     *   and the target picker is opened once.
     */
    private fun refreshDevices() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                tvDeviceCount.text = "正在刷新设备位置..."

                val targetId = targetStore.targetDeviceId
                val result = findMyService.refreshDevices(
                    locateAllDevices = false,
                    deviceId = targetId
                )

                result.onSuccess { response ->
                    lastFetchedDevices = response.devices

                    if (targetId == null) {
                        // First run: nothing saved yet. Show everything
                        // and ask the user to pick the target (their Mac).
                        updateDeviceList(response.devices)
                        updateMapMarkers(response.devices)
                        tvDeviceCount.text = "共 ${response.devices.size} 个设备，请选择目标设备"
                        if (!firstRunPickerShown && response.devices.isNotEmpty()) {
                            firstRunPickerShown = true
                            showTargetPicker(response.devices)
                        }
                        return@onSuccess
                    }

                    val filteredDevices = DeviceFilter.keepTarget(response.devices, targetId)

                    // WGS84 coordinates pass straight through (batch 1).
                    updateDeviceList(filteredDevices)
                    updateMapMarkers(filteredDevices)

                    val totalDevices = filteredDevices.size
                    val onlineDevices = filteredDevices.count { it.location != null }
                    tvDeviceCount.text = "共 $totalDevices 个设备，$onlineDevices 个在线"

                    if (totalDevices == 0) {
                        tvDeviceCount.text = "未找到目标设备"
                        if (refreshCount == 0) {
                            Toast.makeText(this@MapActivity, "未找到目标设备", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { error ->
                    tvDeviceCount.text = "加载失败"
                    if (refreshCount <= 1) {
                        Toast.makeText(
                            this@MapActivity,
                            "刷新失败: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                tvDeviceCount.text = "加载失败"
                if (refreshCount <= 1) {
                    Toast.makeText(
                        this@MapActivity,
                        "刷新失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * Fetch the account's devices without selectedDevice narrowing and
     * open the picker. Used by the "change target" button, where the
     * currently saved target must not hide the other candidates.
     */
    private fun fetchDevicesForPicker() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                val result = findMyService.refreshDevices(
                    locateAllDevices = false,
                    deviceId = null
                )
                result.onSuccess { response ->
                    lastFetchedDevices = response.devices
                    if (response.devices.isEmpty()) {
                        Toast.makeText(this@MapActivity, "未找到任何设备", Toast.LENGTH_SHORT).show()
                    } else {
                        showTargetPicker(response.devices)
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this@MapActivity,
                        "刷新失败: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MapActivity,
                    "刷新失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * Target-device picker: lists devices as name + deviceClass + model.
     * The chosen device's stable DeviceData.id is persisted via
     * TargetDeviceStore, and the view is refreshed in narrowed mode.
     */
    private fun showTargetPicker(devices: List<DeviceData>) {
        val labels = devices.map { DeviceFilter.pickerLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择目标设备")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                targetStore.targetDeviceId = chosen.id
                Toast.makeText(
                    this,
                    "目标设备: ${chosen.name}",
                    Toast.LENGTH_SHORT
                ).show()
                refreshDevices()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateDeviceList(devices: List<DeviceData>) {
        deviceAdapter.submitList(devices)
    }

    /**
     * Redraw markers (keyed by device id) and fit the camera to them.
     * A single marker is centred at a fixed zoom instead of being
     * bounds-fitted (a one-point bounds has no area).
     */
    private fun updateMapMarkers(devices: List<DeviceData>) {
        val map = mapLibreMap
        if (map == null || !isStyleLoaded) {
            pendingMarkerDevices = devices
            return
        }

        map.clear()
        markers.clear()

        val located = devices.filter { device ->
            device.location?.isValid() == true
        }
        if (located.isEmpty()) {
            return
        }

        val boundsBuilder = LatLngBounds.Builder()
        located.forEach { device ->
            val location = device.location!!
            val position = LatLng(location.latitude, location.longitude)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(device.deviceDisplayName.ifEmpty { device.name })
                    .snippet("电量: ${device.batteryLevel}% | ${location.getFormattedTime()}")
            )
            markers[device.id] = marker
            boundsBuilder.include(position)
        }

        try {
            if (located.size == 1) {
                val location = located[0].location!!
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        15.0
                    )
                )
            } else {
                val bounds = boundsBuilder.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            }
        } catch (e: Exception) {
        }
    }

    private fun showDeviceOnMap(device: DeviceData) {
        val map = mapLibreMap
        device.location?.let { location ->
            if (location.isValid() && map != null) {
                val position = LatLng(location.latitude, location.longitude)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(position, 15.0)
                )
                markers[device.id]?.showInfoWindow(map, mapView)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            } else {
                Toast.makeText(
                    this,
                    "该设备位置不可用",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } ?: run {
            Toast.makeText(
                this,
                "该设备没有位置信息",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun playSound(device: DeviceData) {
        lifecycleScope.launch {
            try {
                Toast.makeText(
                    this@MapActivity,
                    "正在向 ${device.name} 发送播放声音请求...",
                    Toast.LENGTH_SHORT
                ).show()

                val result = findMyService.playSound(device.id)

                result.onSuccess {
                    Toast.makeText(
                        this@MapActivity,
                        "已成功向 ${device.name} 发送播放声音请求",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@MapActivity,
                        "播放声音失败: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MapActivity,
                    "播放声音失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        fabRefresh.isEnabled = !show
    }
}
