package com.proxy.client

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.proxy.client.core.config.RouteMode
import com.proxy.client.core.engine.EngineState
import com.proxy.client.core.engine.TrafficStats
import com.proxy.client.core.parser.NodeUriParser
import com.proxy.client.core.speedtest.LatencyTester
import com.proxy.client.data.repository.NodeRepository
import com.proxy.client.databinding.ActivityMainBinding
import com.proxy.client.databinding.DialogImportSubscriptionBinding
import com.proxy.client.service.LocalVpnService
import com.proxy.client.ui.NodeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: NodeRepository
    private lateinit var adapter: NodeAdapter

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startProxyService()
        } else {
            Toast.makeText(this, "需要授予 VPN 权限方可开启代理连接", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = NodeRepository.getInstance(this)
        setupUI()
        observeData()
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)

        adapter = NodeAdapter(
            onNodeSelected = { node ->
                repository.selectNode(node.tag)
                Toast.makeText(this, "已选择节点: ${node.tag}", Toast.LENGTH_SHORT).show()
            },
            onNodeLongClicked = { node ->
                showDeleteNodeDialog(node.tag)
            }
        )

        binding.rvNodes.layoutManager = LinearLayoutManager(this)
        binding.rvNodes.adapter = adapter

        binding.btnConnectToggle.setOnClickListener {
            toggleVpn()
        }

        binding.btnImport.setOnClickListener {
            showImportDialog()
        }

        binding.btnClearAll.setOnClickListener {
            showClearAllDialog()
        }

        binding.btnSpeedTest.setOnClickListener {
            runSpeedTest()
        }

        binding.toggleRouteMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnModeGlobal -> RouteMode.GLOBAL
                    R.id.btnModeDirect -> RouteMode.DIRECT
                    else -> RouteMode.RULE
                }
                repository.setRouteMode(mode)
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.nodeList.collect { nodes ->
                adapter.submitList(ArrayList(nodes))
                binding.tvNodeCount.text = "节点列表 (${nodes.size})"
                binding.tvEmptyState.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            repository.activeNodeTag.collect { tag ->
                adapter.setActiveTag(tag)
            }
        }

        lifecycleScope.launch {
            repository.latencyMap.collect { latencies ->
                adapter.setLatencies(latencies)
            }
        }

        lifecycleScope.launch {
            repository.appProxyConfig.collect { config ->
                when (config.routeMode) {
                    RouteMode.RULE -> binding.toggleRouteMode.check(R.id.btnModeRule)
                    RouteMode.GLOBAL -> binding.toggleRouteMode.check(R.id.btnModeGlobal)
                    RouteMode.DIRECT -> binding.toggleRouteMode.check(R.id.btnModeDirect)
                }
            }
        }

        lifecycleScope.launch {
            LocalVpnService.vpnState.collect { state ->
                updateVpnStateUI(state)
            }
        }

        lifecycleScope.launch {
            LocalVpnService.vpnStats.collect { stats ->
                updateTrafficStatsUI(stats)
            }
        }

        lifecycleScope.launch {
            LocalVpnService.lastError.collect { err ->
                if (!err.isNullOrEmpty()) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("VPN 启动异常")
                        .setMessage(err)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }

    private fun toggleVpn() {
        val currentState = LocalVpnService.vpnState.value
        if (currentState == EngineState.RUNNING || currentState == EngineState.STARTING) {
            LocalVpnService.stopService(this)
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startProxyService()
            }
        }
    }

    private fun startProxyService() {
        val activeNode = repository.getActiveNode()
        if (activeNode == null) {
            Toast.makeText(this, "请先在下方列表中勾选一个节点", Toast.LENGTH_SHORT).show()
            return
        }

        LocalVpnService.pendingNode = activeNode
        LocalVpnService.pendingRouteMode = repository.appProxyConfig.value.routeMode
        LocalVpnService.startService(this)
    }

    private fun updateVpnStateUI(state: EngineState) {
        val dotColor: Int
        val statusText: String
        val btnText: String
        val btnColor: Int

        when (state) {
            EngineState.RUNNING -> {
                dotColor = R.color.status_connected
                statusText = getString(R.string.status_connected)
                btnText = getString(R.string.disconnect)
                btnColor = R.color.status_error
            }
            EngineState.STARTING -> {
                dotColor = R.color.status_connecting
                statusText = getString(R.string.status_connecting)
                btnText = "连接中..."
                btnColor = R.color.status_connecting
            }
            EngineState.STOPPING -> {
                dotColor = R.color.status_connecting
                statusText = "正在断开..."
                btnText = "断开中..."
                btnColor = R.color.status_connecting
            }
            EngineState.ERROR -> {
                dotColor = R.color.status_error
                statusText = "连接异常"
                btnText = getString(R.string.connect)
                btnColor = R.color.primary
            }
            EngineState.STOPPED -> {
                dotColor = R.color.status_disconnected
                statusText = getString(R.string.status_disconnected)
                btnText = getString(R.string.connect)
                btnColor = R.color.primary
            }
        }

        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
        binding.tvStatus.text = statusText
        binding.btnConnectToggle.text = btnText
        binding.btnConnectToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, btnColor))
    }

    private fun updateTrafficStatsUI(stats: TrafficStats) {
        binding.tvUpSpeed.text = formatSpeed(stats.uplinkSpeed)
        binding.tvDownSpeed.text = formatSpeed(stats.downlinkSpeed)
        binding.tvTotalUp.text = "累计: ${formatBytes(stats.totalUplink)}"
        binding.tvTotalDown.text = "累计: ${formatBytes(stats.totalDownlink)}"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun showImportDialog() {
        val dialogBinding = DialogImportSubscriptionBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnPasteClipboard.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString().orEmpty()
                dialogBinding.etInput.setText(clipText)
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnConfirmImport.setOnClickListener {
            val content = dialogBinding.etInput.text?.toString().orEmpty().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "请输入或粘贴订阅链接/节点链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            processImportContent(content)
        }

        dialog.show()
    }

    private fun processImportContent(content: String) {
        lifecycleScope.launch {
            if (content.startsWith("http://", ignoreCase = true) || content.startsWith("https://", ignoreCase = true)) {
                Toast.makeText(this@MainActivity, "正在下载并解析订阅 (支持 Clash/V2Ray/SingBox)...", Toast.LENGTH_SHORT).show()
                try {
                    val nodes = withContext(Dispatchers.IO) {
                        NodeUriParser.fetchSubscription(content)
                    }
                    if (nodes.isNotEmpty()) {
                        repository.addNodes(nodes)
                        Toast.makeText(this@MainActivity, "成功导入 ${nodes.size} 个节点", Toast.LENGTH_SHORT).show()
                    } else {
                        showErrorDialog("解析失败", "未能从该订阅链接中解析出有效节点，请确认订阅是否包含节点或尝试直接复制订阅内容/节点链接导入。")
                    }
                } catch (e: Exception) {
                    showErrorDialog("订阅拉取失败", "错误信息: ${e.localizedMessage ?: e.message}")
                }
            } else {
                val nodes = NodeUriParser.parseSubscription(content)
                if (nodes.isNotEmpty()) {
                    repository.addNodes(nodes)
                    Toast.makeText(this@MainActivity, "成功导入 ${nodes.size} 个节点", Toast.LENGTH_SHORT).show()
                } else {
                    val singleNode = NodeUriParser.parseUri(content)
                    if (singleNode != null) {
                        repository.addNodes(listOf(singleNode))
                        Toast.makeText(this@MainActivity, "成功导入节点: ${singleNode.tag}", Toast.LENGTH_SHORT).show()
                    } else {
                        showErrorDialog("节点识别失败", "未识别到有效的配置。\n支持格式：\n• Clash YAML (proxies)\n• SingBox / Xray JSON\n• Base64 订阅\n• vless://, vmess://, hy2://, tuic://, ss://, trojan://")
                    }
                }
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun runSpeedTest() {
        val nodes = repository.nodeList.value
        if (nodes.isEmpty()) {
            Toast.makeText(this, "列表暂无节点，请先导入节点", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在进行并发测速...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                LatencyTester.testAllNodes(nodes)
            }
            repository.updateLatencies(results)
            Toast.makeText(this@MainActivity, "测速完成", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteNodeDialog(tag: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除节点")
            .setMessage("确定要删除节点【$tag】吗？")
            .setPositiveButton("删除") { _, _ ->
                repository.deleteNode(tag)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空所有节点")
            .setMessage("确定要清空所有节点吗？此操作不可撤销。")
            .setPositiveButton("清空") { _, _ ->
                repository.clearAllNodes()
                Toast.makeText(this, "已清空所有节点", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}