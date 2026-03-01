package com.nova.novastream

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.nova.novastream.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val database = FirebaseDatabase.getInstance()
    private var currentStreamUrl = ""
    private var isViewingChannels = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        
        // Security: Prevent Screenshots (FLAG_SECURE)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        
        setContentView(binding.root)

        // Hide System UI (Modern Way - No Deprecated Flags)
        hideSystemUI()

        // 1. VPN Check
        if (isVpnActive()) {
            showVpnError()
            return
        }

        // 2. Network Monitor
        monitorNetwork()

        // 3. Logic
        logUserInfo()
        checkUpdate()
        setupPlayer()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        loadCategories()

        // 4. Navigation
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tv -> { isViewingChannels = false; loadCategories(); true }
                R.id.nav_radio -> { Toast.makeText(this, "Radio Mode", Toast.LENGTH_SHORT).show(); true }
                R.id.nav_exit -> { finishAffinity(); true }
                else -> false
            }
        }

        // 5. External Player (Intent Action Send)
        binding.btnFullscreen.setOnClickListener {
            if (currentStreamUrl.isNotEmpty()) {
                player?.stop()
                openExternalPlayer(currentStreamUrl)
            } else {
                Toast.makeText(this, "Select a channel first", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 6. Back Press Handling (Modern Way - No deprecated onBackPressed)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isViewingChannels) {
                    isViewingChannels = false
                    loadCategories()
                } else {
                    finish()
                }
            }
        })
    }

    private fun hideSystemUI() {
        // Uses WindowInsetsControllerCompat (API 34 compliant)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun monitorNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (player?.playbackState == Player.STATE_IDLE && currentStreamUrl.isNotEmpty()) {
                        player?.prepare()
                        player?.play()
                    }
                }
            }
            override fun onLost(network: Network) {
                runOnUiThread { Toast.makeText(this@MainActivity, "No Internet Connection", Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun loadCategories() {
        binding.progressBar.visibility = View.VISIBLE
        database.getReference("Superiptv").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = ArrayList<ListItem>()
                for (child in snapshot.children) {
                    val name = child.key ?: "Unknown"
                    val img = child.child("image").getValue(String::class.java) ?: ""
                    list.add(ListItem(name, "", img, isCategory = true))
                }
                binding.recyclerView.adapter = UniversalAdapter(list) { item -> loadChannels(item.name) }
                binding.progressBar.visibility = View.GONE
            }
            override fun onCancelled(error: DatabaseError) { binding.progressBar.visibility = View.GONE }
        })
    }

    private fun loadChannels(categoryName: String) {
        binding.progressBar.visibility = View.VISIBLE
        database.getReference("Superiptv").child(categoryName).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = ArrayList<ListItem>()
                for (child in snapshot.children) {
                    if (child.hasChild("url")) {
                        val name = child.child("name").getValue(String::class.java) ?: "Channel"
                        val url = child.child("url").getValue(String::class.java) ?: ""
                        val img = child.child("logo").getValue(String::class.java) ?: ""
                        list.add(ListItem(name, url, img, isCategory = false))
                    }
                }
                isViewingChannels = true
                binding.recyclerView.adapter = UniversalAdapter(list) { item -> playChannel(item.url) }
                binding.progressBar.visibility = View.GONE
            }
            override fun onCancelled(error: DatabaseError) { binding.progressBar.visibility = View.GONE }
        })
    }

    private fun logUserInfo() {
        val ref = database.getReference("users").push()
        val info = mapOf("model" to Build.MODEL, "android" to Build.VERSION.RELEASE, "time" to System.currentTimeMillis())
        ref.setValue(info)
    }

    private fun checkUpdate() {
        database.getReference("version").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sVer = snapshot.getValue(Int::class.java) ?: 0
                if (sVer > 1) {
                    database.getReference("Mise ajour").get().addOnSuccessListener { 
                        val url = it.getValue(String::class.java)
                        if (!url.isNullOrEmpty()) showUpdateDialog(url)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setupPlayer() {
        // Performance: Fast Buffer (1s)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 30000, 1000, 1000)
            .build()
            
        // Data Saver: Limit resolution to SD (480p)
        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMaxVideoSize(854, 480)
                .setForceHighestSupportedBitrate(false)
                .build()
        )

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
        
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Auto Retry
                Handler(Looper.getMainLooper()).postDelayed({ player?.prepare(); player?.play() }, 3000)
            }
        })
        binding.playerView.player = player
    }

    private fun playChannel(url: String) {
        currentStreamUrl = url
        val mediaItem = MediaItem.Builder().setUri(url).setMimeType(if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null).build()
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun openExternalPlayer(url: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_TEXT, url)
                setPackage("com.exo.novaplayer") // Target Specific App
            }
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            else { 
                // Fallback to chooser
                intent.package = null
                startActivity(Intent.createChooser(intent, "Open with...")) 
            }
        } catch (e: Exception) { Toast.makeText(this, "Error opening player", Toast.LENGTH_SHORT).show() }
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun showVpnError() {
        AlertDialog.Builder(this)
            .setTitle("Security Alert")
            .setMessage("VPN usage is restricted. Please disable VPN to proceed.")
            .setCancelable(false)
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showUpdateDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Required")
            .setMessage("A new version is available.")
            .setCancelable(false)
            .setPositiveButton("Download") { _, _ ->
                val request = DownloadManager.Request(Uri.parse(url))
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "update.apk")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}

data class ListItem(val name: String, val url: String, val image: String, val isCategory: Boolean)

class UniversalAdapter(private val list: List<ListItem>, private val onClick: (ListItem) -> Unit) : RecyclerView.Adapter<UniversalAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val img: ImageView = v.findViewById(R.id.imgIcon)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_list, parent, false)
        return Holder(v)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = list[position]
        holder.name.text = item.name
        Glide.with(holder.itemView.context)
             .load(item.image)
             .diskCacheStrategy(DiskCacheStrategy.ALL)
             .override(100, 100)
             .placeholder(R.mipmap.ic_launcher)
             .into(holder.img)
        holder.itemView.setOnClickListener { onClick(item) }
    }
    override fun getItemCount() = list.size
}