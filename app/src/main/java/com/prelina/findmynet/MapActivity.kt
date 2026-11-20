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
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
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
 * Refresh: while this activity is in the foreground the displayed
 * devices are re-located on a timer whose interval the user controls
 * (v0.1.2). The settings live in [RefreshSettingsStore]: an on/off
 * switch plus an interval picked from [RefreshSettings] presets,
 * defaulting to on / 120 s. The 120 s default was set in batch 2 (the
 * upstream polled every 30 s): only one device is located now
 * (server-side, via selectedDevice), so 30 s polling bought nothing
 * but extra requests against Apple's rate limits and extra battery
 * use. The manual refresh button works regardless of the switch.
 *
 * Tabs (v0.1.3): the screen has two tabs. "My Mac" is the view
 * described above (the saved target device, narrowed server-side).
 * "Family" shows the devices shared with the user through Family
 * Sharing: a fetch with fmly = true, filtered client-side to
 * fmlyShare devices by [DeviceFilter.familyOnly]. The auto-refresh
 * settings are per tab, and the timer always follows the ACTIVE
 * tab's settings and fetches the active tab's data set; switching
 * tabs restarts the timer under the newly active tab's settings.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MapActivity"

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
    private lateinit var refreshSettingsStore: RefreshSettingsStore

    // UI components
    private lateinit var toolbar: Toolbar
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var tabLayout: TabLayout
    private lateinit var btnChangeTarget: Button
    private lateinit var switchAutoRefresh: SwitchCompat
    private lateinit var btnInterval: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvDeviceCount: TextView
    private lateinit var progressBar: ProgressBar

    // Tabs: the list, markers, status line and auto-refresh controls
    // all follow the active tab. Each tab keeps its own last
    // displayed data set, so switching back redraws instantly and a
    // fetch that completes after a tab switch cannot clobber the
    // other tab's view (it only updates its own cache).
    private var activeTab: RefreshTab = RefreshTab.MY_MAC
    private var myMacDevices: List<DeviceData> = emptyList()
    private var familyDevices: List<DeviceData> = emptyList()
    private var familyFetched = false

    // True while the switch/interval controls are being synced to a
    // newly active tab, so the sync does not fire their listeners.
    private var syncingTabControls = false

    // Map markers, keyed by DeviceData.id
    private val markers = mutableMapOf<String, Marker>()

    // Devices waiting to be drawn because the map/style was not ready yet
    private var pendingMarkerDevices: List<DeviceData>? = null
    private var isStyleLoaded = false

    // Last unfiltered device list seen, used by the target picker
    private var lastFetchedDevices: List<DeviceData> = emptyList()
    private var firstRunPickerShown = false

    // Foreground auto-refresh timer. isTimerRunning tracks whether
    // the runnable is currently scheduled; whether it may run at all
    // is the user's persisted preference in RefreshSettingsStore.
    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false
    private var refreshCount = 0

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            // Re-read the ACTIVE tab's persisted settings every cycle,
            // so a change takes effect without restarting the app even
            // if a UI path forgot to restart the timer explicitly.
            // The timer fetches the active tab's data set only.
            val tab = activeTab
            if (!refreshSettingsStore.autoRefreshEnabled(tab)) {
                isTimerRunning = false
                return
            }
            refreshCount++
            refreshActiveTab()
            handler.postDelayed(
                this,
                refreshSettingsStore.intervalSeconds(tab) * 1000L
            )
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
        refreshSettingsStore = RefreshSettingsStore(applicationContext)

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

    /**
     * Schedule the auto-refresh timer under the ACTIVE tab's settings.
     * Called from onResume and after a tab switch or settings change;
     * a no-op when the timer is already running or the active tab's
     * auto-refresh is switched off in the persisted settings.
     */
    private fun startAutoRefresh() {
        if (isTimerRunning) {
            return
        }
        if (!refreshSettingsStore.autoRefreshEnabled(activeTab)) {
            return
        }
        isTimerRunning = true
        refreshCount = 0
        // First auto refresh is delayed: onCreate already refreshed.
        handler.postDelayed(
            autoRefreshRunnable,
            refreshSettingsStore.intervalSeconds(activeTab) * 1000L
        )
    }

    /** Stop the timer (lifecycle pause/destroy, or the switch going off). */
    private fun stopAutoRefresh() {
        isTimerRunning = false
        handler.removeCallbacks(autoRefreshRunnable)
    }

    /**
     * Apply the current persisted settings to the live timer after the
     * user flips the switch or picks a new interval: drop any pending
     * tick and, when auto-refresh is on, schedule fresh with the
     * current interval. These controls are only reachable while the
     * activity is resumed, so rescheduling here is safe.
     */
    private fun applyRefreshSettingsToTimer() {
        stopAutoRefresh()
        startAutoRefresh()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        fabRefresh = findViewById(R.id.fabRefresh)
        tabLayout = findViewById(R.id.tabLayout)
        btnChangeTarget = findViewById(R.id.btnChangeTarget)
        switchAutoRefresh = findViewById(R.id.switchAutoRefresh)
        btnInterval = findViewById(R.id.btnInterval)
        recyclerView = findViewById(R.id.recyclerViewDevices)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        progressBar = findViewById(R.id.progressBar)
        mapView = findViewById(R.id.mapView)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // The manual refresh button refreshes the ACTIVE tab.
        fabRefresh.setOnClickListener {
            refreshActiveTab()
        }

        // Change the target device later: fetch the full device list
        // (no selectedDevice narrowing) and open the picker again.
        btnChangeTarget.setOnClickListener {
            fetchDevicesForPicker()
        }

        initTabs()

        // Auto-refresh controls. They always show and edit the ACTIVE
        // tab's settings. Reflect the persisted settings first, then
        // attach listeners so the initial state does not fire them.
        syncTabControls()
        switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            if (syncingTabControls) {
                return@setOnCheckedChangeListener
            }
            refreshSettingsStore.setAutoRefreshEnabled(activeTab, isChecked)
            // Off stops the timer immediately; on restarts it with the
            // current interval (applyRefreshSettingsToTimer handles both).
            applyRefreshSettingsToTimer()
        }
        btnInterval.setOnClickListener {
            showIntervalPicker()
        }
    }

    /** Build the two tabs and wire selection to [onActiveTabChanged]. */
    private fun initTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("My Mac"))
        tabLayout.addTab(tabLayout.newTab().setText("Family"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val newTab =
                    if (tab.position == 1) RefreshTab.FAMILY else RefreshTab.MY_MAC
                if (newTab != activeTab) {
                    activeTab = newTab
                    onActiveTabChanged()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        })
    }

    /**
     * A different tab became active: sync the auto-refresh controls
     * to that tab's settings, redraw its cached data set, restart
     * the timer under its settings, and fetch on first use (the
     * Family tab fetches when first opened; the My Mac tab fetches
     * if the first-run picker is still outstanding, which a quick
     * tab switch could otherwise strand).
     */
    private fun onActiveTabChanged() {
        syncTabControls()
        displayActiveTabDevices()
        applyRefreshSettingsToTimer()
        when (activeTab) {
            RefreshTab.FAMILY -> {
                if (!familyFetched) {
                    refreshFamilyDevices()
                }
            }
            RefreshTab.MY_MAC -> {
                if (targetStore.targetDeviceId == null && !firstRunPickerShown) {
                    refreshDevices()
                }
            }
        }
    }

    /** Reflect the active tab's persisted settings in the controls. */
    private fun syncTabControls() {
        syncingTabControls = true
        switchAutoRefresh.isChecked =
            refreshSettingsStore.autoRefreshEnabled(activeTab)
        updateIntervalButtonLabel()
        syncingTabControls = false
    }

    /** Redraw the active tab's cached data set (list, markers, status). */
    private fun displayActiveTabDevices() {
        when (activeTab) {
            RefreshTab.MY_MAC -> {
                updateDeviceList(myMacDevices)
                updateMapMarkers(myMacDevices)
                tvDeviceCount.text = myMacStatusText()
            }
            RefreshTab.FAMILY -> {
                updateDeviceList(familyDevices)
                updateMapMarkers(familyDevices)
                tvDeviceCount.text = familyStatusText()
            }
        }
    }

    private fun myMacStatusText(): String {
        if (targetStore.targetDeviceId == null) {
            return "${myMacDevices.size} devices found. Please choose the target device."
        }
        if (myMacDevices.isEmpty()) {
            return "Target device not found"
        }
        val onlineDevices = myMacDevices.count { it.location != null }
        return "${myMacDevices.size} device(s), $onlineDevices online"
    }

    private fun familyStatusText(): String {
        if (!familyFetched) {
            return "Family devices not loaded yet"
        }
        if (familyDevices.isEmpty()) {
            return "No family devices are shared with you"
        }
        val onlineDevices = familyDevices.count { it.location != null }
        return "${familyDevices.size} family device(s), $onlineDevices online"
    }

    /** Fetch the active tab's data set (timer tick and manual refresh). */
    private fun refreshActiveTab() {
        when (activeTab) {
            RefreshTab.MY_MAC -> refreshDevices()
            RefreshTab.FAMILY -> refreshFamilyDevices()
        }
    }

    private fun updateIntervalButtonLabel() {
        btnInterval.text =
            "Every ${RefreshSettings.intervalLabel(refreshSettingsStore.intervalSeconds(activeTab))}"
    }

    /**
     * Single-choice dialog over the preset intervals, editing the
     * ACTIVE tab's interval. Choosing one persists it for that tab
     * and, when that tab's auto-refresh is on, restarts the timer
     * with the new interval.
     */
    private fun showIntervalPicker() {
        val presets = RefreshSettings.INTERVAL_PRESETS_SECONDS
        val labels = presets.map { RefreshSettings.intervalLabel(it) }.toTypedArray()
        val checked = presets.indexOf(refreshSettingsStore.intervalSeconds(activeTab))
        AlertDialog.Builder(this)
            .setTitle("Auto-refresh interval")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                refreshSettingsStore.setIntervalSeconds(activeTab, presets[which])
                updateIntervalButtonLabel()
                applyRefreshSettingsToTimer()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
     * Refresh the target device's location (the My Mac tab's fetch).
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
     *
     * The result is cached in [myMacDevices]; the UI is updated only
     * while the My Mac tab is active, so a fetch completing after a
     * tab switch cannot clobber the Family tab's view.
     */
    private fun refreshDevices() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                if (activeTab == RefreshTab.MY_MAC) {
                    tvDeviceCount.text = "Refreshing device location..."
                }

                val targetId = targetStore.targetDeviceId
                val result = findMyService.refreshDevices(
                    locateAllDevices = false,
                    deviceId = targetId
                )

                result.onSuccess { response ->
                    lastFetchedDevices = response.devices

                    if (targetId == null) {
                        // First run: nothing saved yet. Cache everything
                        // and, while this tab is active, show it all and
                        // ask the user to pick the target (their Mac).
                        myMacDevices = response.devices
                        if (activeTab == RefreshTab.MY_MAC) {
                            updateDeviceList(response.devices)
                            updateMapMarkers(response.devices)
                            tvDeviceCount.text = myMacStatusText()
                            if (!firstRunPickerShown && response.devices.isNotEmpty()) {
                                firstRunPickerShown = true
                                showTargetPicker(response.devices)
                            }
                        }
                        return@onSuccess
                    }

                    val filteredDevices = DeviceFilter.keepTarget(response.devices, targetId)

                    // WGS84 coordinates pass straight through (batch 1).
                    myMacDevices = filteredDevices
                    if (activeTab == RefreshTab.MY_MAC) {
                        updateDeviceList(filteredDevices)
                        updateMapMarkers(filteredDevices)
                        tvDeviceCount.text = myMacStatusText()

                        if (filteredDevices.isEmpty() && refreshCount == 0) {
                            Toast.makeText(this@MapActivity, "Target device not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { error ->
                    if (activeTab == RefreshTab.MY_MAC) {
                        tvDeviceCount.text = "Failed to load"
                        if (refreshCount <= 1) {
                            Toast.makeText(
                                this@MapActivity,
                                "Refresh failed: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {
                if (activeTab == RefreshTab.MY_MAC) {
                    tvDeviceCount.text = "Failed to load"
                    if (refreshCount <= 1) {
                        Toast.makeText(
                            this@MapActivity,
                            "Refresh failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * Refresh the Family tab's devices.
     *
     * The fetch asks Apple for family devices too (fmly = true, no
     * selectedDevice narrowing) and the result is filtered
     * client-side to devices shared through Family Sharing
     * ([DeviceFilter.familyOnly]). Called when the Family tab is
     * first opened, by the manual refresh button while that tab is
     * active, and by the auto-refresh timer under the Family tab's
     * own settings. The My Mac tab's data and narrowing are not
     * touched.
     */
    private fun refreshFamilyDevices() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                if (activeTab == RefreshTab.FAMILY) {
                    tvDeviceCount.text = "Refreshing family devices..."
                }

                val result = findMyService.refreshDevices(
                    locateAllDevices = true,
                    deviceId = null
                )

                result.onSuccess { response ->
                    familyDevices = DeviceFilter.familyOnly(response.devices)
                    familyFetched = true
                    if (activeTab == RefreshTab.FAMILY) {
                        updateDeviceList(familyDevices)
                        updateMapMarkers(familyDevices)
                        tvDeviceCount.text = familyStatusText()
                    }
                }.onFailure { error ->
                    if (activeTab == RefreshTab.FAMILY) {
                        tvDeviceCount.text = "Failed to load"
                        if (refreshCount <= 1) {
                            Toast.makeText(
                                this@MapActivity,
                                "Refresh failed: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {
                if (activeTab == RefreshTab.FAMILY) {
                    tvDeviceCount.text = "Failed to load"
                    if (refreshCount <= 1) {
                        Toast.makeText(
                            this@MapActivity,
                            "Refresh failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
                        Toast.makeText(this@MapActivity, "No devices found", Toast.LENGTH_SHORT).show()
                    } else {
                        showTargetPicker(response.devices)
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this@MapActivity,
                        "Refresh failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MapActivity,
                    "Refresh failed: ${e.message}",
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
            .setTitle("Choose target device")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                targetStore.targetDeviceId = chosen.id
                Toast.makeText(
                    this,
                    "Target device: ${chosen.name}",
                    Toast.LENGTH_SHORT
                ).show()
                refreshDevices()
            }
            .setNegativeButton("Cancel", null)
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
                    .snippet("Battery: ${device.batteryLevel}% | ${location.getFormattedTime()}")
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
                    "Location is not available for this device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } ?: run {
            Toast.makeText(
                this,
                "This device has no location information",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun playSound(device: DeviceData) {
        lifecycleScope.launch {
            try {
                Toast.makeText(
                    this@MapActivity,
                    "Sending a Play Sound request to ${device.name}...",
                    Toast.LENGTH_SHORT
                ).show()

                val result = findMyService.playSound(device.id)

                result.onSuccess {
                    Toast.makeText(
                        this@MapActivity,
                        "Play Sound request sent to ${device.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@MapActivity,
                        "Play Sound failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MapActivity,
                    "Play Sound failed: ${e.message}",
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
