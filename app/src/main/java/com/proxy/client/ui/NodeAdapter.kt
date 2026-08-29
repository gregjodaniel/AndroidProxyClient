package com.proxy.client.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.proxy.client.R
import com.proxy.client.core.config.model.ProxyNodeConfig
import com.proxy.client.databinding.ItemNodeBinding

class NodeAdapter(
    private val onNodeSelected: (ProxyNodeConfig) -> Unit,
    private val onNodeLongClicked: (ProxyNodeConfig) -> Unit
) : ListAdapter<ProxyNodeConfig, NodeAdapter.NodeViewHolder>(NodeDiffCallback()) {

    private var activeNodeTag: String? = null
    private var latencyMap: Map<String, Int> = emptyMap()

    fun setActiveTag(tag: String?) {
        activeNodeTag = tag
        notifyDataSetChanged()
    }

    fun setLatencies(latencies: Map<String, Int>) {
        latencyMap = latencies
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val binding = ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        val node = getItem(position)
        holder.bind(node, node.tag == activeNodeTag, latencyMap[node.tag])
    }

    inner class NodeViewHolder(private val binding: ItemNodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(node: ProxyNodeConfig, isSelected: Boolean, latency: Int?) {
            binding.tvNodeName.text = node.tag
            binding.tvServerInfo.text = "${node.server}:${node.serverPort}"
            binding.rbSelect.isChecked = isSelected

            val context = binding.root.context

            // Protocol badge color
            val (badgeText, badgeColorRes) = when (node.protocolType) {
                "VLESS" -> "VLESS" to R.color.badge_vless
                "VMess" -> "VMess" to R.color.badge_vmess
                "Hysteria2" -> "HY2" to R.color.badge_hy2
                "TUIC" -> "TUIC" to R.color.badge_tuic
                "Trojan" -> "TROJAN" to R.color.badge_trojan
                "Shadowsocks" -> "SS" to R.color.badge_ss
                else -> node.protocolType to R.color.primary
            }
            binding.tvProtocolBadge.text = badgeText
            binding.tvProtocolBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, badgeColorRes))

            // Latency display
            if (latency == null) {
                binding.tvLatency.text = "--- ms"
                binding.tvLatency.setTextColor(ContextCompat.getColor(context, R.color.ping_timeout))
            } else if (latency < 0) {
                binding.tvLatency.text = "超时"
                binding.tvLatency.setTextColor(ContextCompat.getColor(context, R.color.ping_slow))
            } else {
                binding.tvLatency.text = "${latency} ms"
                val latencyColor = when {
                    latency < 150 -> R.color.ping_fast
                    latency < 350 -> R.color.ping_medium
                    else -> R.color.ping_slow
                }
                binding.tvLatency.setTextColor(ContextCompat.getColor(context, latencyColor))
            }

            // Card highlight
            if (isSelected) {
                binding.cardNode.strokeColor = ContextCompat.getColor(context, R.color.primary)
                binding.cardNode.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            } else {
                binding.cardNode.strokeColor = ContextCompat.getColor(context, R.color.divider)
                binding.cardNode.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            }

            binding.root.setOnClickListener { onNodeSelected(node) }
            binding.root.setOnLongClickListener {
                onNodeLongClicked(node)
                true
            }
        }
    }

    class NodeDiffCallback : DiffUtil.ItemCallback<ProxyNodeConfig>() {
        override fun areItemsTheSame(oldItem: ProxyNodeConfig, newItem: ProxyNodeConfig): Boolean {
            return oldItem.tag == newItem.tag
        }

        override fun areContentsTheSame(oldItem: ProxyNodeConfig, newItem: ProxyNodeConfig): Boolean {
            return oldItem == newItem
        }
    }
}
