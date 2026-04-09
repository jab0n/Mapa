package com.example.mapa

import android.annotation.SuppressLint
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mapa.ui.theme.MapaTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configurar osmdroid user agent
        Configuration.getInstance().userAgentValue = packageName
        
        enableEdgeToEdge()
        setContent {
            MapaTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val permissionState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var homeLocation by remember { mutableStateOf(GeoPoint(40.4167, -3.7037)) } // Madrid por defecto
    var road by remember { mutableStateOf<Road?>(null) }
    var isConfiguringHome by remember { mutableStateOf(false) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Referencia al MapView
    val mapView = remember { MapView(context) }
    val myLocationOverlay = remember { 
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            enableMyLocation()
        }
    }

    // Solicitar ubicación al inicio
    @SuppressLint("MissingPermission")
    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    location?.let {
                        val point = GeoPoint(it.latitude, it.longitude)
                        currentLocation = point
                        mapView.controller.setCenter(point)
                        mapView.controller.setZoom(15.0)
                    }
                }
        } else {
            permissionState.launchMultiplePermissionRequest()
        }
    }

    // Calcular ruta cuando cambian las ubicaciones
    LaunchedEffect(currentLocation, homeLocation) {
        val start = currentLocation
        val end = homeLocation
        if (start != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val roadManager: RoadManager = OSRMRoadManager(context, Configuration.getInstance().userAgentValue)
                    val waypoints = arrayListOf(start, end)
                    val newRoad = roadManager.getRoad(waypoints)
                    withContext(Dispatchers.Main) {
                        road = newRoad
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Ruta a Casa", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.inversePrimary,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FloatingActionButton(
                    onClick = { isConfiguringHome = !isConfiguringHome },
                    containerColor = if (isConfiguringHome) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.inversePrimary
                ) {
                    Icon(Icons.Default.Home, contentDescription = "Configurar Casa")
                }
                FloatingActionButton(
                    onClick = {
                        currentLocation?.let { 
                            mapView.controller.animateTo(it)
                        } ?: run {
                            myLocationOverlay.myLocation?.let { 
                                mapView.controller.animateTo(it)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.inversePrimary
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Mi Ubicación")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { _ ->
                    mapView.apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                        controller.setZoom(12.0)
                        controller.setCenter(homeLocation)
                        
                        // Habilitar el seguimiento de ubicación en el overlay
                        myLocationOverlay.enableMyLocation()
                        myLocationOverlay.enableFollowLocation()
                        
                        overlays.add(myLocationOverlay)
                        
                        // Overlay para capturar clics en el mapa
                        val eventsReceiver = object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                p?.let {
                                    if (isConfiguringHome) {
                                        homeLocation = it
                                        isConfiguringHome = false
                                    }
                                }
                                return true
                            }
                            override fun longPressHelper(p: GeoPoint?): Boolean = false
                        }
                        overlays.add(MapEventsOverlay(eventsReceiver))
                    }
                },
                update = { view ->
                    view.overlays.removeIf { it is Marker || it is Polyline }
                    
                    // Marcador de Casa
                    val homeMarker = Marker(view).apply {
                        position = homeLocation
                        title = "Mi Casa"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    view.overlays.add(homeMarker)

                    // Dibujar la ruta
                    road?.let { r ->
                        val line = Polyline().apply {
                            setPoints(r.mRouteHigh)
                            outlinePaint.color = Color.Blue.toArgb()
                            outlinePaint.strokeWidth = 10f
                        }
                        view.overlays.add(line)
                    }
                    
                    view.invalidate()
                }
            )

            if (isConfiguringHome) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inversePrimary,
                    tonalElevation = 4.dp
                ) {
                    Text(
                        "Toca el mapa para establecer la ubicación de tu casa",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Info de la ruta
            road?.let { r ->
                if (r.mStatus == Road.STATUS_OK) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                    ) {
                        Text(
                            "Distancia: ${"%.2f".format(r.mLength)} km - Tiempo: ${"%.1f".format(r.mDuration/60)} min",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}
